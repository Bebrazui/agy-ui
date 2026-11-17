package dev.trustybird.agy

import okhttp3.Dns
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * DNS-over-TLS (RFC 7858) — тот же протокол, что Android использует
 * в «Частном DNS». Порт 853, TLS, обычный DNS-пакет.
 *
 * Именно поэтому строка вида `dns.dns-ai.ru` из системных настроек
 * не работала как DoH: сервер говорит по DoT, а не по HTTPS.
 */
class DotDns(
    private val host: String,
    private val port: Int = 853,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val id = SecureRandom().nextInt(0xFFFF)
        val query = buildQuery(id, hostname)

        Socket().use { raw ->
            raw.connect(InetSocketAddress(host, port), TIMEOUT)
            raw.soTimeout = TIMEOUT

            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, port, true) as SSLSocket
            ssl.use { socket ->
                // SNI и проверка имени — явно: без этого на части устройств
                // рукопожатие идёт без имени и сервер отдаёт чужой сертификат.
                runCatching {
                    val params = socket.sslParameters
                    params.serverNames = listOf(javax.net.ssl.SNIHostName(host))
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    socket.sslParameters = params
                }
                socket.startHandshake()
                DataOutputStream(socket.outputStream).apply {
                    writeShort(query.size)
                    write(query)
                    flush()
                }
                val input = DataInputStream(socket.inputStream)
                val length = input.readUnsignedShort()
                if (length <= 0) error("DoT: пустой ответ от $host")
                val response = ByteArray(length)
                input.readFully(response)

                val addresses = parseAnswers(response, id)
                if (addresses.isEmpty()) throw UnknownHostException(hostname)
                return addresses
            }
        }
    }

    private fun buildQuery(id: Int, hostname: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val data = DataOutputStream(out)

        data.writeShort(id)
        data.writeShort(0x0100) // recursion desired
        data.writeShort(1)      // qdcount
        data.writeShort(0)      // ancount
        data.writeShort(0)      // nscount
        data.writeShort(0)      // arcount

        hostname.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            data.writeByte(bytes.size)
            data.write(bytes)
        }
        data.writeByte(0)
        data.writeShort(1) // A
        data.writeShort(1) // IN
        data.flush()
        return out.toByteArray()
    }

    private fun parseAnswers(buf: ByteArray, expectId: Int): List<InetAddress> {
        if (buf.size < 12) return emptyList()

        val id = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        if (id != expectId) return emptyList()

        val qdCount = u16(buf, 4)
        val anCount = u16(buf, 6)
        var offset = 12

        repeat(qdCount) { offset = skipName(buf, offset); offset += 4 }

        val result = mutableListOf<InetAddress>()
        repeat(anCount) {
            offset = skipName(buf, offset)
            if (offset + 10 > buf.size) return result
            val type = u16(buf, offset)
            val rdLength = u16(buf, offset + 8)
            val rdataStart = offset + 10

            if (type == 1 && rdLength == 4 && rdataStart + 4 <= buf.size) {
                val ip = byteArrayOf(buf[rdataStart], buf[rdataStart + 1], buf[rdataStart + 2], buf[rdataStart + 3])
                runCatching { InetAddress.getByAddress(ip) }.getOrNull()?.let { result += it }
            }
            offset = rdataStart + rdLength
        }
        return result
    }

    private fun skipName(buf: ByteArray, start: Int): Int {
        var offset = start
        while (offset < buf.size) {
            val len = buf[offset].toInt() and 0xFF
            if (len == 0) return offset + 1
            if (len and 0xC0 == 0xC0) return offset + 2 // указатель на сжатое имя
            offset += len + 1
        }
        return offset
    }

    private fun u16(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)

    companion object {
        private const val TIMEOUT = 8_000
    }
}
