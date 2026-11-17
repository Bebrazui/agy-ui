package dev.trustybird.agy

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress

/**
 * DNS-резолвер через DoH (DNS over HTTPS) в формате JSON API.
 * Позволяет не зависеть от системного резолвера при скачивании и диагностике.
 * На сам процесс agy не влияет: он резолвит имена сам.
 */
class DohDns(
    private val endpoint: String,
    private val client: OkHttpClient,
) : Dns {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun lookup(hostname: String): List<InetAddress> {
        val sep = if ('?' in endpoint) '&' else '?'
        val url = "$endpoint${sep}name=$hostname&type=A"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("DoH HTTP ${resp.code}")
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
        /** Из «1.1.1.1» делает «https://1.1.1.1/dns-query»; полный URL оставляет как есть. */
        fun endpointOf(servers: List<String>): String? {
            val first = servers.firstOrNull()?.trim().orEmpty()
            if (first.isEmpty()) return null
            return if (first.startsWith("http")) first else "https://$first/dns-query"
        }
    }
}
