package com.ock.terminal

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Ubuntu ARM64 user-space environment. No Android root is required. */
class ProotEnvironment(context: Context) {
    private val app = context.applicationContext
    val base = File(app.filesDir, "linux")
    val rootfs = File(base, "rootfs")
    private val tools = File(base, "tools")
    private val proot = File(tools, "proot")
    private val archive = File(base, "ubuntu-base-arm64.tar.gz")

    fun isInstalled(): Boolean = File(rootfs, "usr/bin/bash").exists() && proot.exists()

    fun install(progress: (String) -> Unit) {
        if (android.os.Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            throw IllegalStateException("Для этой версии нужен Android ARM64 (arm64-v8a).")
        }
        base.mkdirs(); tools.mkdirs()
        if (!proot.exists()) {
            progress("Загрузка PRoot для Android ARM64…")
            val zip = File(base, "proot-aarch64.zip")
            download("https://github.com/ahmed-alnassif/proot/releases/latest/download/proot-aarch64.zip", zip, progress)
            unzip(zip, tools)
            zip.delete()
            val candidate = tools.walkTopDown().firstOrNull { it.isFile && it.name == "proot" }
                ?: throw IllegalStateException("В архиве PRoot не найден исполняемый файл proot.")
            if (candidate.absolutePath != proot.absolutePath) {
                if (proot.exists()) proot.delete()
                candidate.copyTo(proot, overwrite = true)
            }
            proot.setExecutable(true, true)
        }
        if (!File(rootfs, "usr/bin/bash").exists()) {
            progress("Загрузка Ubuntu 24.04 ARM64 (около 30 МБ)…")
            download("https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz", archive, progress)
            val staging = File(base, "rootfs-installing")
            staging.deleteRecursively(); staging.mkdirs()
            progress("Распаковка Ubuntu. Это может занять несколько минут…")
            val p = ProcessBuilder("/system/bin/toybox", "tar", "-xpf", archive.absolutePath, "-C", staging.absolutePath)
                .redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            if (code != 0) throw IllegalStateException("Не удалось распаковать Ubuntu: $output")
            File(staging, "etc/resolv.conf").apply {
                parentFile?.mkdirs()
                writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            }
            File(staging, "root/.bashrc").apply {
                parentFile?.mkdirs()
                appendText("\nexport TERM=xterm-256color\n")
            }
            rootfs.deleteRecursively()
            if (!staging.renameTo(rootfs)) throw IllegalStateException("Не удалось завершить установку rootfs.")
            archive.delete()
        }
        progress("Ubuntu установлена. Подготовка завершена.")
    }

    fun command(home: File): List<String> {
        if (!isInstalled()) throw IllegalStateException("Ubuntu ещё не установлена.")
        val args = mutableListOf(proot.absolutePath, "--link2symlink", "-0", "-r", rootfs.absolutePath)
        listOf("/dev", "/proc", "/sys").forEach { args.addAll(listOf("-b", "$it:$it")) }
        args.addAll(listOf("-b", "${home.absolutePath}:/mnt/ock", "-w", "/root", "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root", "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "/bin/bash", "--login"))
        return args
    }

    private fun download(address: String, target: File, progress: (String) -> Unit) {
        val part = File(target.parentFile, target.name + ".part")
        part.delete()
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 60000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "OCK-Android")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) throw IllegalStateException("Ошибка загрузки: HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            var count = 0L
            BufferedInputStream(connection.inputStream).use { input -> FileOutputStream(part).use { output ->
                val buffer = ByteArray(64 * 1024); var n: Int; var last = 0
                while (input.read(buffer).also { n = it } >= 0) {
                    if (n == 0) continue
                    output.write(buffer, 0, n); count += n
                    if (total > 0) {
                        val percent = (count * 100 / total).toInt()
                        if (percent >= last + 10) { last = percent; progress("Загрузка: $percent%") }
                    }
                }
            } }
            if (!part.renameTo(target)) throw IllegalStateException("Не удалось сохранить загруженный файл.")
        } finally { connection.disconnect() }
    }

    private fun unzip(zip: File, destination: File) {
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val out = File(destination, entry.name).canonicalFile
                if (!out.path.startsWith(destination.canonicalPath + File.separator)) throw IllegalStateException("Некорректный путь в архиве PRoot.")
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                    if (out.name == "proot" || out.name.contains("loader")) out.setExecutable(true, true)
                }
                input.closeEntry()
            }
        }
    }
}
