package dev.trustybird.agy

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Вход в аккаунт силами приложения.
 *
 * Проверено на 1.5.10: без сохранённой сессии agy печатает ссылку Google,
 * где redirect_uri = https://antigravity.google/oauth-callback, и затем ждёт
 * код авторизации в stdin («Or, paste the authorization code here»).
 *
 * Поэтому: показываем ссылку, открываем браузер, а вставленный код пишем
 * в stdin живого процесса. Никакого Termux и никакого VPN.
 */
class AgyLogin(
    private val ctx: Context,
    private val net: NetConfig,
    private val apiKey: String,
) {

    sealed interface Event {
        data class Url(val url: String) : Event
        data class Status(val text: String) : Event
        data class Done(val ok: Boolean, val detail: String) : Event
    }

    private val urlRegex = Regex("""https://[^\s"'<>\\]+""")

    private var process: Process? = null
    private var writer: BufferedWriter? = null

    /** Скопированный из браузера код → в stdin agy. */
    fun submit(code: String) {
        val w = writer ?: return
        runCatching {
            w.write(code.trim())
            w.newLine()
            w.flush()
        }
    }

    fun cancel() {
        runCatching { writer?.close() }
        process?.let {
            it.destroy()
            if (!it.waitFor(1, TimeUnit.SECONDS)) it.destroyForcibly()
        }
        process = null
        writer = null
    }

    fun run(): Flow<Event> = flow {
        val bin = AgyBinary.binary(ctx)
        if (!bin.isFile) {
            emit(Event.Done(false, "agy не установлен"))
            return@flow
        }

        val home = File(ctx.filesDir, "home").apply { mkdirs() }
        val pb = ProcessBuilder(
            bin.absolutePath,
            "-p", "ping",
            "--output-format", "stream-json",
            "--mode", "plan",
        ).apply {
            directory(home)
            redirectErrorStream(true)
            environment().apply {
                putAll(net.toEnv())
                put("HOME", home.absolutePath)
                put("TMPDIR", File(ctx.cacheDir, "tmp").apply { mkdirs() }.absolutePath)
                put("TERM", "dumb")
                put("NO_COLOR", "1")
                if (apiKey.isNotBlank()) put("GEMINI_API_KEY", apiKey)
            }
        }

        val proc = pb.start()
        process = proc
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream))

        var sawInit = false
        var printedUrl = false
        var lastStatus = ""

        proc.inputStream.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                if (line.startsWith("ERROR: logging before google.Init")) continue

                if ("\"event\":\"init\"" in line) {
                    sawInit = true
                    emit(Event.Status("Вход выполнен"))
                    break
                }

                val url = urlRegex.find(line)?.value?.trimEnd('.', ',', ')')
                if (url != null && !printedUrl && "accounts.google.com" in url) {
                    printedUrl = true
                    emit(Event.Url(url))
                    continue
                }

                if ("Waiting for authentication" in line) {
                    emit(Event.Status("Жду код из браузера"))
                    continue
                }

                if ("paste the authorization code" in line) {
                    emit(Event.Status("Вставь код из браузера"))
                    continue
                }

                if (line.startsWith("{") || line.startsWith("ERROR:")) continue

                val short = line.trim().take(160)
                if (short.isNotEmpty() && short != lastStatus) {
                    lastStatus = short
                    emit(Event.Status(short))
                }
            }
        }

        val code = proc.waitFor()
        writer = null
        process = null

        if (sawInit) {
            emit(Event.Done(true, "Готово"))
        } else {
            emit(Event.Done(false, "agy завершился с кодом $code"))
        }
    }.flowOn(Dispatchers.IO)
}
