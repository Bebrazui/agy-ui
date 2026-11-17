package dev.trustybird.agy

/**
 * Диагностика: где именно рвётся — имя, TLS или IP.
 * Отвечает на вопрос «прокси нужен или хватит чего-то другого» без гаданий.
 */
object NetDiag {

    private const val MANIFEST =
        "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/android_arm64.json"

    private const val STORAGE =
        "https://storage.googleapis.com/antigravity-public/antigravity-cli/"

    private const val GENERIC =
        "https://www.google.com/generate_204"

    fun run(net: NetConfig): String {
        val results = listOf(
            Http.probe(net, MANIFEST),
            Http.probe(net, STORAGE),
            Http.probe(net, GENERIC),
        )
        val proxy = net.proxyUrl()?.replace(Regex("//.*@"), "//***@") ?: "напрямую"
        return buildString {
            appendLine("Соединение: $proxy")
            results.forEach { appendLine(it.toString()) }
            append(diagnose(results))
        }
    }

    private fun diagnose(r: List<ProbeResult>): String {
        if (r.all { it.ok }) return "Всё доступно: сеть в порядке."

        val dnsFail = r.any { it.detail.startsWith("UnknownHost") }
        val timeout = r.any { it.detail.startsWith("SocketTimeout") || it.millis > 15_000 }
        val reset = r.any { it.detail.contains("Reset") || it.detail.contains("refused") }

        return when {
            dnsFail -> "Похоже на DNS: имя не резолвится. Смени резолвер — но это не поможет, " +
                "если блокировка по SNI/IP."
            reset -> "Соединение рвётся после установки — типичный DPI по SNI. " +
                "Нужен прокси на уровне TLS, DNS тут бессилен."
            timeout -> "Таймаут без ответа — блок по IP или throttling. " +
                "Нужен прокси/VPN, имя резолвится нормально."
            else -> "Частичная доступность: часть эндпоинтов открыта. Смотри строки выше."
        }
    }
}
