package com.bonoskkm15.phonerelay.core

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.util.UUID

/**
 * 모든 수집기가 공통으로 쓰는 봉투(phone_event/1).
 * 전송 모듈은 이 JSON만 다룬다.
 */
data class PhoneEvent(
    val source: String,          // 수집기 ID: card_tx, sms, location …
    val type: String,            // 수집기 안의 이벤트 종류
    val trigger: String,         // event | periodic | manual
    val occurredAt: String,      // 실제 발생 시각 (ISO-8601, +09:00)
    val data: JSONObject,
    val eventId: String = UUID.randomUUID().toString(),
    val collectedAt: String = Time.nowIso(),
) {
    fun toJson(device: DeviceInfo): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("event_id", eventId)
        .put("source", source)
        .put("type", type)
        .put("trigger", trigger)
        .put("occurred_at", occurredAt)
        .put("collected_at", collectedAt)
        .put(
            "device", JSONObject()
                .put("id", device.id)
                .put("model", device.model)
                .put("app_version", device.appVersion)
        )
        .put("data", data)

    companion object {
        const val SCHEMA = "phone_event/1"
        const val TRIGGER_EVENT = "event"
        const val TRIGGER_PERIODIC = "periodic"
        const val TRIGGER_MANUAL = "manual"
    }
}

data class DeviceInfo(val id: String, val model: String, val appVersion: String) {
    companion object {
        fun of(ctx: Context): DeviceInfo {
            val version = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
            } catch (e: Exception) {
                "?"
            }
            return DeviceInfo(AppSettings(ctx).deviceId, Build.MODEL, version)
        }
    }
}
