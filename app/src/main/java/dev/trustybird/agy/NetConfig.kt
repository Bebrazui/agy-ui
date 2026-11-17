package dev.trustybird.agy

import android.content.Context

enum class ProxyKind { DIRECT, HTTP, SOCKS5 }

/**
 * Сетевые настройки. Прокси отдаём бинарю через переменные окружения —
 * Go-бинарь уважает HTTPS_PROXY/ALL_PROXY на уровне net/http.
 * DNS пока только косвенно: через socks5h имя резолвит прокси.
 */
data class NetConfig(
    val kind: ProxyKind = ProxyKind.DIRECT,
    val host: String = "",
    val port: Int = 0,
    val user: String = "",
    val pass: String = "",
    val dns: List<String> = emptyList(),
    val remoteDns: Boolean = true,
) {
    val isProxy: Boolean get() = kind != ProxyKind.DIRECT && host.isNotBlank() && port > 0

    fun proxyUrl(): String? {
        if (!isProxy) return null
        val scheme = if (kind == ProxyKind.SOCKS5) "socks5h" else "http"
        val auth = if (user.isNotBlank()) "${user}:${pass}@" else ""
        return "$scheme://$auth$host:$port"
    }

    fun toEnv(): Map<String, String> {
        val url = proxyUrl() ?: return emptyMap()
        return mapOf(
            "HTTP_PROXY" to url,
            "HTTPS_PROXY" to url,
            "ALL_PROXY" to url,
            "http_proxy" to url,
            "https_proxy" to url,
            "all_proxy" to url,
            "NO_PROXY" to "localhost,127.0.0.1",
            "no_proxy" to "localhost,127.0.0.1",
        )
    }

    companion object {
        fun load(ctx: Context): NetConfig {
            val p = ctx.getSharedPreferences("net", Context.MODE_PRIVATE)
            return NetConfig(
                kind = runCatching { ProxyKind.valueOf(p.getString("kind", "DIRECT")!!) }
                    .getOrDefault(ProxyKind.DIRECT),
                host = p.getString("host", "") ?: "",
                port = p.getInt("port", 0),
                user = p.getString("user", "") ?: "",
                pass = p.getString("pass", "") ?: "",
                dns = (p.getString("dns", "") ?: "")
                    .split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() },
                remoteDns = p.getBoolean("remoteDns", true),
            )
        }

        fun save(ctx: Context, cfg: NetConfig) {
            ctx.getSharedPreferences("net", Context.MODE_PRIVATE).edit()
                .putString("kind", cfg.kind.name)
                .putString("host", cfg.host)
                .putInt("port", cfg.port)
                .putString("user", cfg.user)
                .putString("pass", cfg.pass)
                .putString("dns", cfg.dns.joinToString(","))
                .putBoolean("remoteDns", cfg.remoteDns)
                .apply()
        }
    }
}
