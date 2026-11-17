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
 * Проверено на 1.5.10: `agy acp --login` работает без терминала —
 * печатает ссылку Google, затем читает из stdin код авторизации
 * (или полный URL страницы, на которую пришёл браузер).
 *
 * redirect_uri = https://antigravity.google/oauth-callback — это веб-страница,
 * поэтому браузер не вернётся в приложение сам: код копируется вручную.
 *
 * Всё пишется в files/login.log — видно в «Настройки → Логи».
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

    @Volatile
    private var success = false

    private fun log(line: String) {
        runCatching { logFile.appendText(line.take(2000) + "\n") }
    }

    /** Код из браузера (или полный URL страницы) → в stdin agy. */
    fun submit(text: String): Boolean {
        val w = writer ?: return false
        return runCatching {
            w.write(text.trim())
            w.newLine()
            w.flush()
            log(">>> отправлено (${text.length} симв.)")
            true
        }.getOrElse {
            log("!!! не удалось отправить: ${it.javaClass.simpleName}: ${it.message}")
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
        log("=== вход ${java.util.Date()} ===")

        val bin = AgyBinary.binary(ctx)
        if (!bin.isFile) {
            emit(Event.Done(false, "agy не установлен"))
            return@flow
        }
        runCatching { android.system.Os.chmod(bin.absolutePath, 493) }

        val home = File(ctx.filesDir, "home").apply { mkdirs() }
        val useKey = apiKey.isNotBlank()
        val args = if (useKey) {
            listOf("-p", "ping", "--output-format", "stream-json", "--mode", "plan")
        } else {
            listOf("acp", "--login")
        }
        val pb = ProcessBuilder(listOf(bin.absolutePath) + args).apply {
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
        log("команда: ${pb.command().joinToString(" ")}")

        val proc = try {
            pb.start()
        } catch (e: Exception) {
            log("!!! не стартовал: ${e.javaClass.name}: ${e.message}")
            emit(Event.Done(false, "Не удалось запустить agy: ${e.message}"))
            return@flow
        }
        process = proc
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
        log("процесс запущен")

        // Страховка: если agy зависнет на ожидании, не держим вечно.
        val watchdog = Thread {
            try {
                if (!proc.waitFor(5, TimeUnit.MINUTES)) {
                    log("!!! таймаут ожидания входа — убиваю процесс")
                    proc.destroyForcibly()
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        var printedUrl = false
        var lastStatus = ""

        try {
            proc.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    log(line)

                    if (line.startsWith("ERROR: logging before google.Init")) continue

                    if ("\"event\":\"init\"" in line || "\"status\":\"SUCCESS\"" in line) {
                        success = true
                        continue
                    }

                    val url = urlRegex.find(line)?.value?.trimEnd('.', ',', ')')
                    if (url != null && !printedUrl && "accounts.google.com" in url) {
                        printedUrl = true
                        emit(Event.Url(url))
                        continue
                    }

                    val lower = line.lowercase()
                    when {
                        "paste the authorization code" in lower -> {
                            emit(Event.Status("Вставь код или адрес страницы"))
                            continue
                        }
                        "login cancelled" in lower -> {
                            emit(Event.Status("Вход отменён"))
                            continue
                        }
                        "logged in" in lower || "signed in" in lower || "authenticated" in lower -> {
                            success = true
                        }
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
        log("=== код выхода $code, успех=$success, ссылка была=$printedUrl ===")

        if (success || code == 0) {
            emit(Event.Done(true, "Готово"))
        } else {
            emit(Event.Done(false, "Вход не завершён (код $code). Смотри Логи."))
        }
    }.flowOn(Dispatchers.IO)
}
