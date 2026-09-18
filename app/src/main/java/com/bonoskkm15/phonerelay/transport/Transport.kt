package com.bonoskkm15.phonerelay.transport

import android.content.Context
import com.bonoskkm15.phonerelay.core.AppSettings
import com.bonoskkm15.phonerelay.core.EventQueue
import com.bonoskkm15.phonerelay.core.SecretStore
import java.io.IOException

/** 전송 모듈 공통 인터페이스. 구현체를 바꿔도 수집기·큐 코드는 그대로다. */
interface Transport {
    /**
     * 배치를 보내고 서버가 받은 event_id 집합을 돌려준다.
     * 연결 실패 등 배치 전체가 실패하면 [TransportException]을 던진다.
     */
    fun send(batch: List<EventQueue.Row>): Set<String>
}

class TransportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 설정이 비어 있으면 null (전송하지 않고 큐에 쌓아 둔다). */
object Transports {
    fun create(ctx: Context): Transport? {
        val s = AppSettings(ctx)
        return when (s.transport) {
            AppSettings.TRANSPORT_SYSLOG ->
                if (s.syslogHost.isBlank()) null
                else SyslogTcpTransport(s.syslogHost, s.syslogPort, s.deviceId)

            else -> {
                val token = SecretStore.loadToken(ctx)
                if (s.httpsUrl.isBlank() || token.isNullOrBlank()) null
                else HttpsJsonTransport(s.httpsUrl, token)
            }
        }
    }
}
