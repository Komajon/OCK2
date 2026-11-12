package com.ock.terminal

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private data class AppItem(val name: String, val cmd: String, val source: String = "Встроенная")
    private val bg = Color.rgb(8, 12, 22)
    private val panel = Color.rgb(18, 26, 43)
    private val panel2 = Color.rgb(23, 34, 55)
    private val blue = Color.rgb(45, 111, 255)
    private val cyan = Color.rgb(0, 204, 255)
    private val white = Color.rgb(235, 242, 255)
    private val muted = Color.rgb(142, 159, 190)
    private val green = Color.rgb(49, 220, 139)
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("ock", MODE_PRIVATE) }
    private val history = mutableListOf<String>()
    private val linux by lazy { ProotEnvironment(this) }
    private var histPos = -1
    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private lateinit var body: FrameLayout
    private lateinit var terminalOutput: TextView
    private lateinit var terminalScroll: ScrollView
    private lateinit var commandInput: EditText
    private lateinit var status: TextView
    private val navButtons = mutableListOf<TextView>()
    private var selectedPage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        buildShell()
        showHome()
    }

    override fun onDestroy() {
        process?.destroy()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun d(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 18, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = d(radius).toFloat()
            if (stroke != 0) setStroke(d(1), stroke)
        }
    private fun text(value: String, size: Float, color: Int = white, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
    private fun column(pad: Int = 18) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(d(pad), d(pad), d(pad), d(pad))
    }
    private fun addGap(parent: LinearLayout, h: Int = 12) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, d(h)))
    }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit): TextView =
        text(label, 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(d(16), d(13), d(16), d(13))
            background = rounded(if (primary) blue else panel2, 14)
            setOnClickListener { action() }
        }
    private fun card(): LinearLayout = column(16).apply { background = rounded(panel, 20) }
    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(20), d(16), d(20), d(12))
        }
        val logo = TextView(this).apply {
            text = "🐧"; textSize = 28f
        }
        header.addView(logo)
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10),0,0,0) }
        brand.addView(text("OCK", 22f, white, true))
        brand.addView(text("LINUX • ANDROID", 10f, cyan, true))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("⚙", false) { settingsDialog() })
        root.addView(header)
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(d(7), d(7), d(7), d(7))
            background = rounded(panel, 20)
        }
        navButtons.clear()
        listOf("⌂\nГлавная", "▦\nПрограммы", "🐧\nUbuntu", ">_\nТерминал").forEachIndexed { i, label ->
            val item = text(label, 12f, muted, true).apply {
                gravity = Gravity.CENTER
                setPadding(d(4), d(10), d(4), d(10))
                background = rounded(Color.TRANSPARENT, 14)
                setOnClickListener { when(i) { 0 -> showHome(); 1 -> showApps(); 2 -> showLinux(); else -> showTerminal() } }
            }
            navButtons.add(item)
            nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(d(3), 0, d(3), 0)
            })
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(d(12), d(4), d(12), d(8))
        })
        setContentView(root)
    }
    private fun setPage(view: View) {
        body.removeAllViews()
        body.addView(view, FrameLayout.LayoutParams(-1, -1))
        navButtons.forEachIndexed { index, item ->
            val active = index == selectedPage
            item.setTextColor(if (active) white else muted)
            item.background = rounded(if (active) Color.rgb(35, 67, 119) else Color.TRANSPARENT, 14)
        }
    }
    private fun scroll(content: View): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(content, ViewGroup.LayoutParams(-1,-2))
    }
    private fun apps(): MutableList<AppItem> {
        val result = mutableListOf<AppItem>()
        val raw = prefs.getString("apps", null)
        if (raw == null) {
            result.add(AppItem("Neofetch", "neofetch"))
            result.add(AppItem("Файлы", "ls -la"))
            result.add(AppItem("Система", "uname -a && df -h"))
            saveApps(result)
        } else try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                result.add(AppItem(o.getString("name"), o.getString("cmd"), o.optString("source","GitHub")))
            }
        } catch (_: Exception) {}
        return result
    }
    private fun saveApps(list: List<AppItem>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name",it.name).put("cmd",it.cmd).put("source",it.source)) }
        prefs.edit().putString("apps", a.toString()).apply()
    }
    private fun showHome() {
        selectedPage = 0
        val c = column(18)
        val hero = column(20).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(19,45,91), Color.rgb(25,25,62))).apply { cornerRadius=d(24).toFloat() }
        }
        hero.addView(text("LINUX WORKSPACE  /  OCK", 10f, cyan, true))
        hero.addView(text("Твой Linux.\nТвои правила.", 27f, white, true))
        hero.addView(text("Запускай инструменты, управляй файлами и работай в терминале.", 13f, Color.rgb(185, 199, 224)))
        hero.addView(button("＋  Установить с GitHub", true) { githubDialog() })
        c.addView(hero)
        addGap(c, 14)
        val overview = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(panel, 18)
            setPadding(d(14), d(13), d(14), d(13))
        }
        val linuxInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        linuxInfo.addView(text("СРЕДА", 9f, muted, true))
        linuxInfo.addView(text("Android shell", 14f, white, true))
        overview.addView(linuxInfo, LinearLayout.LayoutParams(0, -2, 1f))
        val stateInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        stateInfo.addView(text("СОСТОЯНИЕ", 9f, muted, true))
        stateInfo.addView(text(if (linux.isInstalled()) "●  Ubuntu готова" else "●  Shell готов", 13f, green, true))
        overview.addView(stateInfo)
        c.addView(overview)
        addGap(c,10)
        c.addView(button(if (linux.isInstalled()) "🐧  Открыть Ubuntu" else "⬇  Установить Ubuntu ARM64", true) { if (linux.isInstalled()) { showLinux(); } else installUbuntu() })
        addGap(c,18)
        c.addView(text("Быстрый запуск",18f,white,true))
        addGap(c,8)
        val list = apps()
        val grid = GridLayout(this).apply { columnCount = 2 }
        list.forEach { item ->
            val tile = column(14).apply {
                background = rounded(panel2,18,Color.rgb(35,58,93))
                addView(text("▣",24f,cyan,true))
                addGap(this,5)
                addView(text(item.name,15f,white,true))
                addView(text(item.source,10f,muted))
                setOnClickListener { runApp(item) }
                setOnLongClickListener { appActions(item); true }
            }
            val lp = GridLayout.LayoutParams().apply {
                width=0; height=-2; columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f)
                setMargins(d(4),d(4),d(4),d(4))
            }
            grid.addView(tile,lp)
        }
        val add = column(14).apply {
            background = rounded(Color.TRANSPARENT,18,blue)
            addView(text("＋",26f,cyan,true)); addView(text("Добавить",14f,cyan,true))
            setOnClickListener { githubDialog() }
        }
        grid.addView(add,GridLayout.LayoutParams().apply {
            width=0;height=-2;columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);setMargins(d(4),d(4),d(4),d(4))
        })
        c.addView(grid)
        addGap(c,12)
        c.addView(text("Удерживай карточку, чтобы изменить или удалить ярлык.",11f,muted))
        setPage(scroll(c))
    }
    private fun showApps() {
        selectedPage = 1
        val c=column(18)
        c.addView(text("Программы",26f,white,true))
        c.addView(text("Твои инструменты и команды запуска",12f,muted))
        addGap(c)
        c.addView(button("＋  Установить программу",true){githubDialog()})
        addGap(c)
        apps().forEach { item ->
            val row=LinearLayout(this).apply {
                gravity=Gravity.CENTER_VERTICAL; background=rounded(panel,16); setPadding(d(14),d(12),d(10),d(12))
            }
            val info=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
            info.addView(text("▣  ${item.name}",15f,white,true))
            info.addView(text(item.source+" • нажми для запуска",11f,muted))
            row.addView(info,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(button("Открыть",true){runApp(item)})
            row.setOnLongClickListener { appActions(item); true }
            c.addView(row); addGap(c,8)
        }
        setPage(scroll(c))
    }
    private fun showLinux() {
        selectedPage = 2
        val c = column(18)
        c.addView(text("Ubuntu Linux", 26f, white, true))
        c.addView(text("Изолированная ARM64-среда на базе Ubuntu 24.04 и PRoot", 12f, muted))
        addGap(c, 16)
        val info = card()
        info.addView(text("СОСТОЯНИЕ СРЕДЫ", 10f, cyan, true))
        info.addView(text(if (linux.isInstalled()) "● Ubuntu установлена" else "○ Ubuntu ещё не установлена", 17f, if (linux.isInstalled()) green else white, true))
        info.addView(text("Файлы среды хранятся в приватной памяти OCK и сохраняются после перезапуска приложения.", 12f, muted))
        c.addView(info)
        addGap(c, 12)
        c.addView(button(if (linux.isInstalled()) "Открыть терминал Ubuntu" else "Установить Ubuntu ARM64", true) {
            if (linux.isInstalled()) { startUbuntuShell(); showTerminal() } else installUbuntu()
        })
        addGap(c, 8)
        c.addView(button("Обновить пакеты (apt update)", false) { if (linux.isInstalled()) runUbuntuCommand("apt update") else Toast.makeText(this, "Сначала установи Ubuntu", Toast.LENGTH_SHORT).show() })
        addGap(c, 8)
        c.addView(button("Установить Python 3", false) { if (linux.isInstalled()) runUbuntuCommand("apt update && apt install -y python3 python3-pip") else Toast.makeText(this, "Сначала установи Ubuntu", Toast.LENGTH_SHORT).show() })
        addGap(c, 16)
        c.addView(text("Ограничения", 16f, white, true))
        c.addView(text("PRoot не требует root-доступа, но не является полноценной виртуальной машиной. systemd и некоторые функции ядра Linux недоступны.", 12f, muted))
        setPage(scroll(c))
    }

    private fun installUbuntu() {
        AlertDialog.Builder(this).setTitle("Установить Ubuntu ARM64?")
            .setMessage("Будет загружен базовый образ Ubuntu (примерно 30 МБ), затем он распакуется во внутреннюю память приложения. После установки можно будет использовать apt и Python.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Установить") { _, _ ->
                showTerminal()
                append("\nOCK Linux installer\n")
                worker.execute {
                    try {
                        linux.install { message -> ui.post { append(message + "\n") } }
                        ui.post { append("✓ Ubuntu готова. Запускаю bash…\n"); startUbuntuShell(); showTerminal() }
                    } catch (e: Exception) { ui.post { append("ОШИБКА УСТАНОВКИ: ${e.message}\n") } }
                }
            }.show()
    }

    private fun startUbuntuShell() {
        process?.destroy(); process = null; stdin = null
        try {
            val home = File(filesDir, "home").apply { mkdirs() }
            val pb = ProcessBuilder(linux.command(home)).redirectErrorStream(true).directory(home)
            val loader = File(filesDir, "linux/tools").walkTopDown().firstOrNull { it.isFile && it.name == "loader" }
            if (loader != null) pb.environment()["PROOT_LOADER"] = loader.absolutePath
            val p = pb.start(); process = p; stdin = BufferedWriter(OutputStreamWriter(p.outputStream))
            Thread {
                try { val reader = BufferedReader(InputStreamReader(p.inputStream)); val buffer = CharArray(2048)
                    while (true) { val n = reader.read(buffer); if (n < 0) break; val chunk = String(buffer, 0, n); ui.post { append(chunk) } }
                } catch (_: Exception) { }
            }.start()
            append("\n[Ubuntu ARM64 / PRoot]\n")
        } catch (e: Exception) { append("Не удалось запустить Ubuntu: ${e.message}\n") }
    }

    private fun runUbuntuCommand(command: String) {
        if (!linux.isInstalled()) return
        showTerminal()
        if (process == null || process?.isAlive != true) startUbuntuShell()
        commandInput.setText(command); sendCommand()
    }

    private fun showTerminal() {
        selectedPage = 3
        val c=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(d(12),d(8),d(12),d(8)) }
        val top=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        status=text("●  SHELL ACTIVE",11f,green,true); top.addView(status,LinearLayout.LayoutParams(0,-2,1f))
        top.addView(button("Очистить",false){terminalOutput.text=""})
        c.addView(top)
        terminalScroll=ScrollView(this).apply { setBackground(rounded(Color.rgb(3,7,13),16)); isFillViewport=true }
        terminalOutput=text("OCK TERMINAL  •  SESSION\nВведите help для списка команд.\n\n",13f,Color.rgb(190,255,210)).apply {
            typeface=Typeface.MONOSPACE; setPadding(d(14),d(14),d(14),d(14)); setTextIsSelectable(true)
        }
        terminalScroll.addView(terminalOutput)
        c.addView(terminalScroll,LinearLayout.LayoutParams(-1,0,1f).apply { topMargin=d(10); bottomMargin=d(10) })
        val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; background=rounded(panel,16); setPadding(d(6),d(4),d(6),d(4)) }
        commandInput=EditText(this).apply {
            hint="Введите команду…"; setHintTextColor(muted); setTextColor(white); textSize=14f
            typeface=Typeface.MONOSPACE; isSingleLine=true; imeOptions=EditorInfo.IME_ACTION_SEND
            setBackgroundColor(Color.TRANSPARENT)
            setOnEditorActionListener { _,_,_ -> sendCommand(); true }
        }
        row.addView(commandInput,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(button("➜",true){sendCommand()})
        c.addView(row)
        val tools=LinearLayout(this).apply { gravity=Gravity.CENTER }
        tools.addView(button("↑ История",false){recall()})
        tools.addView(button("↻ Shell",false){restartShell()})
        c.addView(tools)
        setPage(c)
        if (process == null || process?.isAlive != true) startShell()
    }
    private fun runApp(item: AppItem) { showTerminal(); commandInput.setText(item.cmd); sendCommand() }
    private fun appActions(item: AppItem) {
        AlertDialog.Builder(this).setTitle(item.name)
            .setItems(arrayOf("Запустить","Изменить ярлык","Удалить ярлык")) { _,which ->
                when(which) { 0 -> runApp(item); 1 -> editApp(item); 2 -> removeApp(item) }
            }.show()
    }
    private fun editApp(item: AppItem) {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(d(20),d(8),d(20),0) }
        val name=EditText(this).apply { setText(item.name); hint="Название" }
        val cmd=EditText(this).apply { setText(item.cmd); hint="Команда запуска" }
        box.addView(name); box.addView(cmd)
        AlertDialog.Builder(this).setTitle("Изменить ярлык").setView(box)
            .setPositiveButton("Сохранить"){_,_->
                val a=apps(); val i=a.indexOfFirst{it.name==item.name && it.cmd==item.cmd}
                if(i>=0 && name.text.isNotBlank() && cmd.text.isNotBlank()) {
                    a[i]=item.copy(name=name.text.toString().trim(),cmd=cmd.text.toString().trim()); saveApps(a); showApps()
                }
            }.setNegativeButton("Отмена",null).show()
    }
    private fun removeApp(item: AppItem) {
        AlertDialog.Builder(this).setMessage("Удалить ярлык ${item.name}? Файлы программы останутся.")
            .setPositiveButton("Удалить"){_,_-> saveApps(apps().filterNot{it==item}); showApps()}
            .setNegativeButton("Отмена",null).show()
    }
    private fun githubDialog() {
        val input=EditText(this).apply {
            hint="owner/repo или https://github.com/owner/repo"
            setSingleLine(true)
        }
        val box=column(8); box.addView(text("Репозиторий GitHub",13f,muted)); box.addView(input)
        AlertDialog.Builder(this).setTitle("Установить программу").setView(box)
            .setPositiveButton("Скачать"){_,_-> installRepo(input.text.toString().trim())}
            .setNegativeButton("Отмена",null).show()
    }
    private fun installRepo(raw: String) {
        val repo=raw.removeSuffix(".git").trimEnd('/').substringAfterLast("github.com/").trim()
        showTerminal()
        append("OCK installer: $repo\n")
        worker.execute {
            try {
                val result=Installer.install(repo,File(filesDir,"apps")){msg->ui.post{append("$msg\n")}}
                ui.post {
                    val a=apps(); a.removeAll{it.name.equals(result.name,true)}
                    a.add(AppItem(result.name,result.cmd,"GitHub")); saveApps(a)
                    append("✓ Установлено: ${result.name}\nЯрлык добавлен на главный экран.\n")
                }
            } catch(e:Exception) { ui.post { append("ОШИБКА: ${e.message}\n") } }
        }
    }
    private fun append(s:String) {
        if (!::terminalOutput.isInitialized) return
        terminalOutput.append(s)
        if(terminalOutput.length()>50000) terminalOutput.text=terminalOutput.text.toString().takeLast(35000)
        if(::terminalScroll.isInitialized) terminalScroll.post{terminalScroll.fullScroll(View.FOCUS_DOWN)}
    }
    private fun startShell() {
        if (linux.isInstalled()) { startUbuntuShell(); return }
        try {
            val home=File(filesDir,"home").apply{mkdirs()}
            val pb=ProcessBuilder("/system/bin/sh").redirectErrorStream(true).directory(home)
            pb.environment()["HOME"]=home.absolutePath; pb.environment()["TERM"]="xterm-256color"
            val p=pb.start(); process=p; stdin=BufferedWriter(OutputStreamWriter(p.outputStream))
            Thread {
                try {
                    val r=BufferedReader(InputStreamReader(p.inputStream)); val b=CharArray(2048)
                    while(true){val n=r.read(b);if(n<0)break;val s=String(b,0,n);ui.post{append(s)}}
                } catch(_:Exception){}
            }.start()
        } catch(e:Exception){ append("Shell error: ${e.message}\n") }
    }
    private fun sendCommand() {
        val cmd=commandInput.text.toString().trim(); if(cmd.isEmpty())return
        history.add(cmd);histPos=-1;commandInput.setText("");append("\n$ $cmd\n")
        val w=stdin
        if(w==null){append("Shell не запущен. Перезапусти его.\n");return}
        worker.execute { try{synchronized(w){w.write(cmd);w.newLine();w.flush()}}catch(e:Exception){ui.post{append("Ошибка: ${e.message}\n")}} }
    }
    private fun recall() {
        if(history.isEmpty())return
        histPos=if(histPos<0)history.lastIndex else (histPos-1).coerceAtLeast(0)
        commandInput.setText(history[histPos]);commandInput.setSelection(commandInput.length())
    }
    private fun restartShell(){process?.destroy();process=null;stdin=null;append("\n--- Перезапуск shell ---\n");startShell()}
    private fun settingsDialog() {
        val options=arrayOf("Размер текста терминала","Очистить историю команд","О приложении")
        AlertDialog.Builder(this).setTitle("Настройки OCK").setItems(options){_,i->
            when(i){
                0 -> {
                    val sizes=arrayOf("Маленький","Обычный","Большой")
                    AlertDialog.Builder(this).setTitle("Размер текста").setItems(sizes){_,n->
                        prefs.edit().putInt("terminal_size",intArrayOf(11,13,16)[n]).apply()
                        if(::terminalOutput.isInitialized) terminalOutput.textSize=intArrayOf(11,13,16)[n].toFloat()
                    }.show()
                }
                1 -> { history.clear(); histPos=-1; Toast.makeText(this,"История очищена",Toast.LENGTH_SHORT).show() }
                2 -> AlertDialog.Builder(this).setMessage("OCK — Linux launcher for Android\nВерсия 2.0").setPositiveButton("OK",null).show()
            }
        }.show()
    }
}
