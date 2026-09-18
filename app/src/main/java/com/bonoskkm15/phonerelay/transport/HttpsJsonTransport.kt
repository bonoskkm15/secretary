package com.bonoskkm15.phonerelay.transport

import com.bonoskkm15.phonerelay.core.EventQueue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * POST <url>  Authorization: Bearer <token>
 * 본문: 봉투 JSON 배열, 응답: {"accepted": ["event_id", ...]}
 */
class HttpsJsonTransport(
    private val url: String,
    private val token: String,
) : Transport {

    override fun send(batch: List<EventQueue.Row>): Set<String> {
        if (batch.isEmpty()) return emptySet()
        val parsedUrl = try {
            URL(url)
        } catch (e: Exception) {
            throw TransportException("잘못된 서버 주소: $url", e)
        }
        if (!parsedUrl.protocol.equals("https", ignoreCase = true)) {
            throw TransportException("HTTPS 주소만 허용됩니다")
        }
        val body = batch.joinToString(separator = ",", prefix = "[", postfix = "]") { it.body }
            .toByteArray(Charsets.UTF_8)

        val conn = try {
            parsedUrl.openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw TransportException("잘못된 서버 주소: $url", e)
        }
        try {
            conn.requestMethod = "POST"
            conn.useCaches = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $token")

            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code == 401) throw TransportException("토큰 인증 실패 (401)")
            if (code !in 200..299) throw TransportException("서버 응답 오류 HTTP $code")

            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val accepted = JSONObject(text).optJSONArray("accepted") ?: return emptySet()
            return (0 until accepted.length()).map { accepted.getString(it) }.toSet()
        } catch (e: TransportException) {
            throw e
        } catch (e: Exception) {
            throw TransportException("전송 실패: ${e.javaClass.simpleName} ${e.message ?: ""}", e)
        } finally {
            conn.disconnect()
        }
    }
}
