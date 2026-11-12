package com.ock.terminal

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Установка программ из GitHub.
 * Скачивает последний релиз (zip-ассет или исходники), распаковывает в apps/<repo>
 * и подбирает скрипт запуска: ock.sh, run.sh, main.sh или первый *.sh в корне.
 */
object Installer {

    class Result(val name: String, val cmd: String, val note: String)

    private val repoPattern = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    private const val USER_AGENT = "OCK-Installer"

    fun install(repo: String, appsDir: File, log: (String) -> Unit): Result {
        require(repoPattern.matches(repo)) { "Неверный формат: нужно owner/repo" }

        val name = repo.substringAfter('/')
        val target = File(appsDir, name)
        val tmpZip = File(appsDir, "$name.zip.part")
        appsDir.mkdirs()

        log("Ищу последний релиз $repo...")
        val url = findZipUrl(repo)

        log("Скачиваю...")
        download(url, tmpZip)

        log("Распаковываю в ${target.absolutePath}")
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        unzip(tmpZip, target)
        tmpZip.delete()

        val script = findScript(target)
        return if (script != null) {
            Result(name, "sh \"${script.absolutePath}\"", "скрипт ${script.name}")
        } else {
            Result(name, "cd \"${target.absolutePath}\" && ls -la", "скрипт не найден, открыт каталог")
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        return c
    }

    private fun findZipUrl(repo: String): String {
        val c = open("https://api.github.com/repos/$repo/releases/latest")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val code = c.responseCode
        if (code == 200) {
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".zip")) {
                        return asset.getString("browser_download_url")
                    }
                }
            }
            return json.getString("zipball_url")
        }
        // Релизов нет: берём ветку по умолчанию
        return "https://api.github.com/repos/$repo/zipball"
    }

    private fun download(url: String, dest: File) {
        val c = open(url)
        c.setRequestProperty("Accept", "*/*")
        val code = c.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code при скачивании")
        c.inputStream.use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out) }
        }
        c.disconnect()
    }

    /** Распаковка. GitHub кладёт всё в одну корневую папку, её срезаем. Защита от zip-slip. */
    private fun unzip(zip: File, dest: File) {
        val base = dest.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val parts = entry.name.split('/', limit = 2)
                val rel = if (parts.size == 2) parts[1] else ""
                if (rel.isNotEmpty()) {
                    val out = File(base, rel).canonicalFile
                    if (out != base && !out.path.startsWith(base.path + File.separator)) {
                        throw SecurityException("Небезопасный путь в архиве: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
                        if (rel.endsWith(".sh") || rel.contains("bin/")) {
                            out.setExecutable(true)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun findScript(dir: File): File? {
        for (n in listOf("ock.sh", "run.sh", "main.sh")) {
            val f = File(dir, n)
            if (f.isFile) return f
        }
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".sh") }
            ?.minByOrNull { it.name }
    }
}
