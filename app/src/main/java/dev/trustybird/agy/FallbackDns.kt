package dev.trustybird.agy

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Список резолверов с фейловером: пробуем по очереди, пока кто-нибудь не ответит.
 * Если не ответил никто — падаем на системный резолвер (по желанию).
 */
class FallbackDns(
    private val resolvers: List<Pair<String, Dns>>,
    private val fallbackToSystem: Boolean = true,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        var lastError: Exception? = null
        for ((label, resolver) in resolvers) {
            try {
                val result = resolver.lookup(hostname)
                if (result.isNotEmpty()) return result
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (fallbackToSystem) return Dns.SYSTEM.lookup(hostname)
        throw UnknownHostException(hostname).also { it.initCause(lastError) }
    }

    companion object {
        /**
         * Понимает форматы:
         *   1.1.1.1                        → сначала DoT:853, потом DoH
         *   dns.dns-ai.ru                  → DoT:853, потом DoH
         *   tls://dns.adguard.com          → только DoT
         *   https://dns.google/dns-query   → только DoH
         *   host:853                       → DoT с указанным портом
         */
        fun fromConfig(
            servers: List<String>,
            httpClient: okhttp3.OkHttpClient,
        ): List<Pair<String, Dns>> = servers
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .flatMap { raw ->
                when {
                    // fallbackToSystem = false: иначе первый же сбой DoH вернул бы
                    // системный ответ и очередь серверов потеряла бы смысл.
                    raw.startsWith("https://") ->
                        listOf(raw to DohDns(listOf(raw), httpClient, fallbackToSystem = false))

                    raw.startsWith("tls://") || raw.startsWith("tcp://") -> {
                        val address = raw.substringAfter("://")
                        val host = address.substringBefore(':')
                        val port = address.substringAfter(':', "853").toIntOrNull() ?: 853
                        listOf(raw to DotDns(host, port))
                    }

                    else -> {
                        val host = raw.substringBefore(':')
                        val port = raw.substringAfter(':', "853").toIntOrNull() ?: 853
                        listOf(
                            "$raw (DoT)" to DotDns(host, port),
                            "https://$host/dns-query (DoH)" to
                                DohDns(listOf("https://$host/dns-query"), httpClient, fallbackToSystem = false),
                        )
                    }
                }
            }
    }
}
