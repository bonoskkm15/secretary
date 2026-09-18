package com.bonoskkm15.phonerelay.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit

/** 앱 설정. 토큰은 [SecretStore]에 따로 암호화해 저장한다. */
class AppSettings(ctx: Context) {
    private val appContext = ctx.applicationContext
    private val p = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var transport: String
        get() = p.getString("transport", TRANSPORT_HTTPS) ?: TRANSPORT_HTTPS
        set(v) = p.edit { putString("transport", v) }

    var httpsUrl: String
        get() = p.getString("https_url", DEFAULT_HTTPS_URL) ?: DEFAULT_HTTPS_URL
        set(v) = p.edit { putString("https_url", v.trim()) }

    var syslogHost: String
        get() = p.getString("syslog_host", "") ?: ""
        set(v) = p.edit { putString("syslog_host", v.trim()) }

    var syslogPort: Int
        get() = p.getInt("syslog_port", 514)
        set(v) = p.edit { putInt("syslog_port", v) }

    var deviceId: String
        get() {
            val saved = p.getString("device_id", null)?.trim()
            return if (!saved.isNullOrEmpty() && saved != LEGACY_DEFAULT_DEVICE_ID) {
                saved
            } else {
                defaultDeviceName(appContext)
            }
        }
        set(v) = p.edit { putString("device_id", v.trim()) }

    var wifiOnly: Boolean
        get() = p.getBoolean("wifi_only", false)
        set(v) = p.edit { putBoolean("wifi_only", v) }

    // 카드 SMS 전용 설정(card_sms_enabled, card_sms_senders)은 규칙 엔진으로 대체되어 제거했다.
    // 발신번호 필터는 이제 규칙의 source.senders 에 들어간다.

    /** 마지막으로 처리한 MMS(LMS) _id. -1이면 아직 초기화 전. */
    var lastMmsId: Long
        get() = p.getLong("last_mms_id", -1L)
        set(v) = p.edit { putLong("last_mms_id", v) }

    // ── 상태 표시용 ──
    var lastSendStatus: String
        get() = p.getString("last_send_status", "아직 전송 기록 없음") ?: ""
        set(v) = p.edit { putString("last_send_status", "${Time.nowIso().take(19).replace('T', ' ')}  $v") }

    companion object {
        const val TRANSPORT_HTTPS = "https"
        const val TRANSPORT_SYSLOG = "syslog"
        const val DEFAULT_HTTPS_URL = "https://api.bonoskkm15.duckdns.org/v1/events"
        private const val LEGACY_DEFAULT_DEVICE_ID = "galaxy-zflip7"

        /** 사용자가 설정한 휴대폰 이름을 우선하고, 없으면 제조사·모델명으로 대체한다. */
        fun defaultDeviceName(ctx: Context): String {
            val systemName = runCatching {
                Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull()?.trim()
            if (!systemName.isNullOrEmpty()) return systemName

            val fallback = listOf(Build.MANUFACTURER, Build.MODEL)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .joinToString(" ")
            return fallback.ifEmpty { "android-device" }
        }
    }
}
