package dev.trustybird.agy

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * DNS через DoH (DNS over HTTPS) со списком серверов и фейловером.
 *
 * Принимает и IP, и полный URL:
 *   1.1.1.1
 *   8.8.8.8
 *   https://dns.google/dns-query
 *
 * Если первый сервер не ответил — пробуем следующий, и так по кругу списка.
 */
class DohDns(
    private val endpoints: List<String>,
    private val client: OkHttpClient,
) : Dns {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    constructor(server: String, client: OkHttpClient) : this(listOf(server), client)

    override fun lookup(hostname: String): List<InetAddress> {
        var lastError: Exception? = null

        for (endpoint in endpoints) {
            try {
                val result = query(endpoint, hostname)
                if (result.isNotEmpty()) return result
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw UnknownHostException(hostname).also { it.initCause(lastError) }
    }

    private fun query(endpoint: String, hostname: String): List<InetAddress> {
        val sep = if ('?' in endpoint) '&' else '?'
        val req = Request.Builder()
            .url("$endpoint${sep}name=$hostname&type=A")
            .header("Accept", "application/dns-json")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("DoH HTTP ${resp.code} @ $endpoint")
            val body = resp.body?.string() ?: error("DoH: пустой ответ")
            val answers = runCatching {
                json.parseToJsonElement(body).jsonObject["Answer"]?.jsonArray
            }.getOrNull() ?: return emptyList()

            return answers.mapNotNull { element ->
                val data = runCatching {
                    element.jsonObject["data"]?.jsonPrimitive?.content
                }.getOrNull() ?: return@mapNotNull null
                runCatching { InetAddress.getByName(data) }.getOrNull()
            }
        }
    }

    companion object {
        /** «1.1.1.1» → «https://1.1.1.1/dns-query»; полный URL оставляем как есть. */
        fun endpointsOf(servers: List<String>): List<String> = servers
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith("http")) it else "https://$it/dns-query" }
            .distinct()
    }
}
