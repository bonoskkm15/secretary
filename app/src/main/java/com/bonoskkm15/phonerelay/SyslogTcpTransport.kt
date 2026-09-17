package com.bonoskkm15.phonerelay.transport

import com.bonoskkm15.phonerelay.core.EventQueue
import com.bonoskkm15.phonerelay.core.Time
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * RFC 5424 헤더 + 봉투 JSON, TCP octet-counting(RFC 6587) 프레이밍.
 * syslog에는 수신 확인이 없으므로 소켓 쓰기 성공을 성공으로 본다.
 * 평문이므로 내부망이나 VPN 안에서만 사용한다.
 */
class SyslogTcpTransport(
    private val host: String,
    private val port: Int,
    hostname: String,
) : Transport {

    private val hostname = printable(hostname, 255)

    override fun send(batch: List<EventQueue.Row>): Set<String> {
        if (batch.isEmpty()) return emptySet()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 5_000)
                socket.soTimeout = 10_000
                val out = BufferedOutputStream(socket.getOutputStream())
                for (row in batch) {
                    val msg = format(row).toByteArray(Charsets.UTF_8)
                    out.write("${msg.size} ".toByteArray(Charsets.US_ASCII))
                    out.write(msg)
                }
                out.flush()
            }
        } catch (e: Exception) {
            throw TransportException("syslog 전송 실패: ${e.javaClass.simpleName} ${e.message ?: ""}", e)
        }
        return batch.map { it.eventId }.toSet()
    }

    private fun format(row: EventQueue.Row): String {
        val source = try {
            JSONObject(row.body).optString("source", "-")
        } catch (e: Exception) {
            "-"
        }
        // <134> = local0.info
        return "<134>1 ${Time.nowIso()} $hostname $APP_NAME - ${printable(source, 32)} - ${row.body}"
    }

    companion object {
        const val APP_NAME = "phonerelay"

        /** RFC 5424 헤더 필드: 공백 없는 ASCII 33~126, 최대 길이 제한, 비면 "-". */
        fun printable(s: String, max: Int): String =
            s.filter { it.code in 33..126 }.take(max).ifEmpty { "-" }
    }
}
