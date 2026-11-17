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
 * код авторизации в stdin.
 *
 * Всё, что происходит, пишется в files/login.log — если что-то упадёт,
 * причина будет видна в «Настройки → Логи».
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
    private val logFile = File(ctx.filesDir, "login.log")

    private var process: Process? = null
    private var writer: BufferedWriter? = null

    private fun log(line: String) {
        runCatching { logFile.appendText(line.take(2000) + "\n") }
    }

    /** Скопированный из браузера код → в stdin agy. */
    fun submit(code: String): Boolean {
        val w = writer ?: return false
        return runCatching {
            w.write(code.trim())
            w.newLine()
            w.flush()
            log(">>> код отправлен (${code.length} симв.)")
            true
        }.getOrElse {
            log("!!! не удалось отправить код: ${it.javaClass.simpleName}: ${it.message}")
            false
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
        log("--- отменено ---")
    }

    fun run(): Flow<Event> = flow {
        runCatching { logFile.writeText("") }
        log("=== старт входа ${java.util.Date()} ===")

        val bin = AgyBinary.binary(ctx)
        log("бинарь: ${bin.absolutePath}, есть=${bin.isFile}, exec=${bin.canExecute()}")

        if (!bin.isFile) {
            emit(Event.Done(false, "agy не установлен"))
            return@flow
        }
        runCatching {
            android.system.Os.chmod(bin.absolutePath, 493) // 0755
        }.onFailure { log("chmod: ${it.message}") }

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
            log("команда: ${command().joinToString(" ")}")
            log("прокси: ${net.proxyUrl()?.replace(Regex("//.*@"), "//***@") ?: "нет"}")
        }

        val proc = try {
            pb.start()
        } catch (e: Exception) {
            log("!!! процесс не стартовал: ${e.javaClass.name}: ${e.message}")
            emit(Event.Done(false, "Не удалось запустить agy: ${e.message}"))
            return@flow
        }

        process = proc
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
        log("процесс запущен, pid=${runCatching { proc.pid() }.getOrDefault(-1)}")

        var sawInit = false
        var printedUrl = false
        var lastStatus = ""

        try {
            proc.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    log(line)

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

                    if (line.startsWith("{")) continue

                    val short = line.trim().take(160)
                    if (short.isNotEmpty() && short != lastStatus) {
                        lastStatus = short
                        emit(Event.Status(short))
                    }
                }
            }
        } catch (e: Exception) {
            log("!!! ошибка чтения: ${e.javaClass.simpleName}: ${e.message}")
        }

        val code = proc.waitFor()
        writer = null
        process = null
        log("=== процесс завершён, код $code, init=$sawInit ===")

        if (sawInit) {
            emit(Event.Done(true, "Готово"))
        } else if (code == 0) {
            emit(Event.Done(false, "Процесс вышел без подтверждения входа"))
        } else {
            emit(Event.Done(false, "agy завершился с кодом $code (смотри Логи)"))
        }
    }.flowOn(Dispatchers.IO)
}
