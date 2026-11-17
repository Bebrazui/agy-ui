package dev.trustybird.agy

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Вход в аккаунт силами самого приложения.
 *
 * Механика: запускаем официальный agy с пустым HOME. Без сохранённой сессии
 * он печатает ссылку авторизации Google — её показываем в UI и открываем
 * в браузере. Дальше процесс ждёт и пишет init-событие, когда вход прошёл.
 * Файлы сессии остаются в песочнице приложения, Termux не участвует.
 */
object AgyLogin {

    sealed interface Event {
        data class Url(val url: String) : Event
        data class Status(val text: String) : Event
        data class Done(val ok: Boolean, val detail: String) : Event
    }

    private val urlRegex = Regex("""https://[^\s"'<>\\]+""")

    fun start(
        ctx: Context,
        net: NetConfig,
        apiKey: String,
    ): Flow<Event> = flow {
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
        proc.outputStream.close()

        var sawInit = false
        proc.inputStream.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue

                if ("\"event\":\"init\"" in line) {
                    sawInit = true
                    emit(Event.Status("Авторизация активна"))
                    continue
                }

                if ("Authentication required" in line || "auth_required" in line) {
                    emit(Event.Status("Нужен вход в Google"))
                    continue
                }

                urlRegex.find(line)?.let { m ->
                    val url = m.value.trimEnd('.', ',', ')')
                    if ("google" in url || "oauth" in url || "login" in url) {
                        emit(Event.Url(url))
                    }
                }

                if (line.startsWith("ERROR: logging before google.Init")) continue
                if (line.startsWith("{")) continue
                emit(Event.Status(line.take(160)))
            }
        }

        val code = proc.waitFor()
        if (sawInit) {
            emit(Event.Done(true, "Вход выполнен"))
        } else {
            emit(Event.Done(false, "agy завершился с кодом $code"))
        }
    }.flowOn(Dispatchers.IO)

    fun kill(process: Process?) {
        process?.let {
            it.destroy()
            if (!it.waitFor(1, TimeUnit.SECONDS)) it.destroyForcibly()
        }
    }
}
