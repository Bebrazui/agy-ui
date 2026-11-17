package dev.trustybird.agy

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Официальная сборка Antigravity CLI для Android (Bionic/arm64).
 * Бинарь не входит в APK: он скачивается с сервера Google и проверяется по sha512.
 */
@Serializable
data class Manifest(val version: String, val url: String, val sha512: String)

object AgyBinary {

    private const val MANIFEST_URL =
        "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/android_arm64.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun binDir(ctx: Context): File = File(ctx.filesDir, "bin")
    fun binary(ctx: Context): File = File(binDir(ctx), "agy")
    fun isInstalled(ctx: Context): Boolean = binary(ctx).let { it.isFile && it.canExecute() }
    fun installedVersion(ctx: Context): String? =
        File(binDir(ctx), "version").takeIf { it.isFile }?.readText()?.trim()

    /** Блокирующий вызов: запускать из Dispatchers.IO. */
    fun install(ctx: Context, net: NetConfig, onProgress: (String, Float?) -> Unit): String {
        val dir = binDir(ctx).apply { mkdirs() }
        onProgress("Читаю манифест…", null)
        val client = Http.client(net)
        val m = json.decodeFromString<Manifest>(httpText(client, MANIFEST_URL))

        val tgz = File(dir, "agy.tar.gz")
        onProgress("Скачиваю agy ${m.version}…", 0f)
        download(client, m.url, tgz) { read, total ->
            val fraction = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else null
            onProgress("agy ${m.version} · ${if (fraction != null) "${(fraction * 100).toInt()}%" else "${read / 1048576} МБ"}", fraction)
        }

        onProgress("Проверяю sha512…", null)
        check(sha512(tgz) == m.sha512.lowercase()) { "sha512 не совпал — скачивание повреждено" }

        onProgress("Распаковываю…", null)
        val staged = File(dir, "agy.staged")
        untarFirstFile(tgz, staged)
        staged.setExecutable(true)
        if (!staged.renameTo(binary(ctx))) {
            staged.copyTo(binary(ctx), overwrite = true)
            staged.delete()
        }
        binary(ctx).setExecutable(true)
        tgz.delete()
        File(dir, "version").writeText(m.version)
        onProgress("Установлено: ${m.version}", 1f)
        return m.version
    }

    fun remove(ctx: Context) {
        binDir(ctx).deleteRecursively()
    }

    private fun httpText(client: okhttp3.OkHttpClient, url: String): String =
        client.newCall(
            Request.Builder().url(url).header("User-Agent", "agy-ui").build(),
        ).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} на $url" }
            resp.body?.string() ?: error("пустой ответ на $url")
        }

    private fun download(
        client: okhttp3.OkHttpClient,
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        client.newCall(
            Request.Builder().url(url).header("User-Agent", "agy-ui").build(),
        ).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} на $url" }
            val total = resp.body?.contentLength() ?: -1L
            val input = resp.body?.byteStream() ?: error("пустой ответ на $url")
            target.outputStream().use { out ->
                val buf = ByteArray(128 * 1024)
                var read = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (read - lastReport > 8 * 1024 * 1024) {
                        lastReport = read
                        onProgress(read, total)
                    }
                }
            }
        }
    }

    private fun sha512(file: File): String {
        val md = MessageDigest.getInstance("SHA-512")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Минимальный tar-распаковщик: берёт первый обычный файл из архива.
     * Полноценный tar не нужен — в релизе лежит ровно один бинарь.
     */
    private fun untarFirstFile(archive: File, out: File) {
        GZIPInputStream(archive.inputStream().buffered()).use { input ->
            val header = ByteArray(512)
            while (true) {
                var read = 0
                while (read < 512) {
                    val n = input.read(header, read, 512 - read)
                    if (n < 0) return
                    read += n
                }
                if (header.all { it == 0.toByte() }) continue

                val type = header[156].toInt().toChar()
                val size = header.copyOfRange(124, 136)
                    .toString(Charsets.US_ASCII).trim { it == '\u0000' || it == ' ' }
                    .toLongOrNull(8) ?: 0L

                if (type == 'L') { // длинное имя: пропускаем данные заголовка
                    skip(input, size)
                    continue
                }

                if (type == '0' || type == '\u0000') {
                    FileOutputStreamShim.write(input, out, size)
                    return
                }
                skip(input, size)
            }
        }
    }

    private fun skip(input: java.io.InputStream, size: Long) {
        var left = size
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return
            left -= n
        }
        val pad = ((512 - size % 512) % 512).toInt()
        var p = pad
        while (p > 0) {
            val n = input.read(buf, 0, minOf(buf.size, p))
            if (n < 0) return
            p -= n
        }
    }
}

private object FileOutputStreamShim {
    fun write(input: java.io.InputStream, out: File, size: Long) {
        val buf = ByteArray(128 * 1024)
        out.outputStream().use { os ->
            var left = size
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                os.write(buf, 0, n)
                left -= n
            }
        }
        val pad = ((512 - size % 512) % 512).toInt()
        var p = pad
        while (p > 0) {
            val n = input.read(buf, 0, minOf(buf.size, p))
            if (n < 0) break
            p -= n
        }
    }
}
