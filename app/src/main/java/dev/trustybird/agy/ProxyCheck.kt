package dev.trustybird.agy

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Один прогон `agy -p ping` с текущими сетевыми настройками.
 *
 * Нужен ровно для одного вопроса: ошибка «недоступно в вашем регионе»
 * зависит от IP или от аккаунта? Если через прокси текст меняется — решает IP.
 * Если остаётся слово в слово — решает аккаунт, и прокси тут бессилен.
 *
 * Ничего не патчит и не обходит: просто запускает штатную команду и
 * раскладывает ответ по полочкам.
 */
object ProxyCheck {

    private const val TIMEOUT_SECONDS = 180L

    fun run(ctx: Context, net: NetConfig): String {
        val bin = AgyBinary.binary(ctx)
        if (!bin.isFile) {
            return "agy ещё не скачан. Сначала запусти приложение с интернетом."
        }
        runCatching { android.system.Os.chmod(bin.absolutePath, 493) }

        val home = File(ctx.filesDir, "home").apply { mkdirs() }
        val tmp = File(ctx.cacheDir, "tmp").apply { mkdirs() }

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
                put("TMPDIR", tmp.absolutePath)
                put("TERM", "dumb")
                put("NO_COLOR", "1")
                Store.apiKey(ctx).takeIf { it.isNotBlank() }?.let { put("GEMINI_API_KEY", it) }
            }
        }

        val proxy = net.proxyUrl()?.replace(Regex("//.*@"), "//***@") ?: "напрямую"

        val proc = runCatching { pb.start() }.getOrElse {
            return "Прокси: $proxy\n\nНе удалось запустить agy: ${it.javaClass.simpleName}: ${it.message}"
        }

        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                proc.inputStream.bufferedReader().use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.isBlank()) continue
                        if (line.startsWith("ERROR: logging before google.Init")) continue
                        synchronized(output) { output.appendLine(line) }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) proc.destroyForcibly()
        reader.join(3_000)

        val raw = synchronized(output) { output.toString() }
        val verdict = classify(raw, net)

        return buildString {
            appendLine("Прокси: $proxy")
            appendLine()
            appendLine(verdict)
            appendLine()
            appendLine("--- ответ agy ---")
            val body = raw.lines()
                .filter { !it.startsWith("{") }
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .ifBlank { raw.take(1200) }
            append(body.take(2500).ifBlank { "пусто" })
            if (!finished) appendLine("\n\n(остановлено по таймауту ${TIMEOUT_SECONDS}s)")
        }
    }

    private fun classify(raw: String, net: NetConfig): String {
        val lower = raw.lowercase()
        val viaProxy = if (net.isProxy) "через прокси" else "без прокси"

        val success = "\"status\":\"success\"" in lower || "\"event\":\"init\"" in lower
        if (success) {
            return "Готово: agy ответил ($viaProxy). Регион не мешает — можно работать."
        }

        val proxyBroken = listOf(
            "proxyconnect", "proxy connect", "connection refused", "connection reset",
            "no route to host", "i/o timeout", "dial tcp", "tls handshake",
            "407", "socks", "unexpected eof",
        ).any { it in lower }

        val location = listOf(
            "not eligible", "eligibility", "not currently available",
            "not available in your location", "unsupported country", "region",
        ).any { it in lower }

        val auth = listOf(
            "run 'agy' to log in", "not logged in", "authentication required",
            "unauthorized", "401", "no credentials", "please log in",
        ).any { it in lower }

        return when {
            location && viaProxy == "через прокси" ->
                "Ответ про регион — и через прокси. Скорее всего решает аккаунт, а не IP. " +
                    "Проверь, что прокси реально за границей (пункт «Проверить» выше показывает IP-адреса)."

            location ->
                "Ответ про регион. Это ожидаемо без прокси. Подключи прокси и запусти снова — " +
                    "тогда будет видно, от чего зависит проверка."

            auth ->
                "agy не считает тебя вошедшим. Дело не в регионе: сначала пройди вход."

            proxyBroken && net.isProxy ->
                "Похоже, прокси не работает: соединение не установилось. Сначала убедись, " +
                    "что он живой — ошибка про регион тут ни при чём."

            raw.isBlank() ->
                "agy ничего не ответил. Смотри «Логи»."

            else ->
                "Ответ не распознан однозначно. Текст ниже — что именно сказал agy."
        }
    }
}
