package dev.trustybird.agy

/**
 * Диагностика: где именно рвётся — DNS-сервер, TLS или IP.
 * Отвечает на вопрос «что чинить» без гаданий.
 */
object NetDiag {

    private const val MANIFEST =
        "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/android_arm64.json"

    private const val STORAGE =
        "https://storage.googleapis.com/antigravity-public/antigravity-cli/"

    private const val GENERIC =
        "https://www.google.com/generate_204"

    private const val TEST_HOST = "www.google.com"

    fun run(net: NetConfig): String {
        val plain = Http.client(net.copy(dns = emptyList()))

        val dnsLines = net.dns.map { server ->
            val endpoint = DohDns.endpointsOf(listOf(server)).firstOrNull() ?: server
            runCatching {
                val found = DohDns(listOf(endpoint), plain, fallbackToSystem = false).lookup(TEST_HOST)
                "✓ $server — отвечает (${found.size} адр.)"
            }.getOrElse {
                "✗ $server — ${it.javaClass.simpleName}: ${it.message?.take(80)}"
            }
        }

        val probes = listOf(
            Http.probe(net, MANIFEST),
            Http.probe(net, STORAGE),
            Http.probe(net, GENERIC),
        )

        val proxy = net.proxyUrl()?.replace(Regex("//.*@"), "//***@") ?: "напрямую"

        return buildString {
            appendLine("Прокси: $proxy")
            appendLine()

            if (dnsLines.isEmpty()) {
                appendLine("DNS: системный")
            } else {
                appendLine("DNS-серверы (DoH):")
                dnsLines.forEach { appendLine("  $it") }
            }
            appendLine()
            appendLine("Эндпоинты:")
            probes.forEach { appendLine("  $it") }
            appendLine()
            append(diagnose(probes, dnsLines))
        }
    }

    private fun diagnose(probes: List<ProbeResult>, dnsLines: List<String>): String {
        if (probes.all { it.ok }) return "Всё доступно: сеть в порядке."

        val dnsAllBroken = dnsLines.isNotEmpty() && dnsLines.all { it.startsWith("✗") }
        if (dnsAllBroken) {
            return "Ни один DoH-сервер не ответил. Возможно, выбранный сервер не умеет DoH " +
                "(подойдут 1.1.1.1, 8.8.8.8) или соединение до него режется. " +
                "Пока приложение падает обратно на системный резолвер."
        }

        val dnsFail = probes.any { it.detail.startsWith("UnknownHost") }
        val timeout = probes.any { it.detail.startsWith("SocketTimeout") || it.millis > 15_000 }
        val reset = probes.any { it.detail.contains("Reset") || it.detail.contains("refused") }

        return when {
            reset -> "Соединение рвётся после установки — похоже на DPI по SNI. " +
                "DNS тут бессилен, нужен прокси."
            timeout -> "Таймаут без ответа — блок по IP или throttling. " +
                "Имена резолвятся, дело не в DNS."
            dnsFail -> "Имя не резолвится: системный DNS не отвечает. " +
                "Укажи DoH-сервер (1.1.1.1) — но при блокировке по SNI/IP это не поможет."
            else -> "Частичная доступность: часть эндпоинтов открыта. Смотри строки выше."
        }
    }
}
