package dev.trustybird.agy

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Один HTTP-клиент на всё приложение: скачивание бинаря, диагностика.
 * Прокси настраивается явно, а не через системные переменные, — так работает
 * и там, где Go-бинарь env-прокси игнорирует.
 */
object Http {

    fun client(net: NetConfig): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)

        if (net.isProxy) {
            val type = if (net.kind == ProxyKind.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP
            builder.proxy(Proxy(type, InetSocketAddress.createUnresolved(net.host, net.port)))
            if (net.user.isNotBlank()) {
                builder.proxyAuthenticator(object : Authenticator {
                    override fun authenticate(route: Route?, response: Response): Request? {
                        if (response.request.header("Proxy-Authorization") != null) return null
                        return response.request.newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(net.user, net.pass))
                            .build()
                    }
                })
            }
        }
        val proxyConfigured = builder.build()
        if (net.dns.isEmpty()) return proxyConfigured

        val resolvers = FallbackDns.fromConfig(net.dns, proxyConfigured)
        if (resolvers.isEmpty()) return proxyConfigured
        return proxyConfigured.newBuilder().dns(FallbackDns(resolvers)).build()
    }

    /** Одноразовая проверка доступности эндпоинта. */
    fun probe(net: NetConfig, url: String): ProbeResult {
        val started = System.currentTimeMillis()
        return try {
            client(net).newCall(
                Request.Builder().url(url).head().build(),
            ).execute().use { resp ->
                ProbeResult(url, true, System.currentTimeMillis() - started, "HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            ProbeResult(url, false, System.currentTimeMillis() - started, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }
}

data class ProbeResult(
    val url: String,
    val ok: Boolean,
    val millis: Long,
    val detail: String,
) {
    override fun toString(): String = (if (ok) "✓" else "✗") + " $millis мс — $detail"
}
