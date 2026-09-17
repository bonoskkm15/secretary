package com.bonoskkm15.phonerelay.collector.cardsms

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.bonoskkm15.phonerelay.core.AppSettings
import com.bonoskkm15.phonerelay.rule.RuleEngine
import com.bonoskkm15.phonerelay.rule.RuleInputFactory

/**
 * 장문(LMS)은 SMS_RECEIVED가 오지 않으므로 MMS 저장소를 스캔한다.
 * [com.bonoskkm15.phonerelay.core.CollectorHostService]의 옵저버와 부팅 시에 호출된다.
 */
object MmsScanner {
    private const val TAG = "MmsScanner"
    private val MMS: Uri = Uri.parse("content://mms")
    private val PART: Uri = Uri.parse("content://mms/part")
    private const val MSG_BOX_INBOX = 1
    private const val ADDR_TYPE_FROM = 137
    private const val PART_WAIT_MS = 10 * 60 * 1000L

    @Synchronized
    fun scan(ctx: Context) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val settings = AppSettings(ctx)
        val cr = ctx.contentResolver
        try {
            val maxId = cr.query(MMS, arrayOf("_id"), null, null, "_id DESC")?.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            } ?: return

            // 처음 실행: 과거 문자는 보내지 않고 기준점만 잡는다.
            if (settings.lastMmsId < 0) {
                settings.lastMmsId = maxId
                return
            }
            if (maxId <= settings.lastMmsId) return

            cr.query(
                MMS, arrayOf("_id", "date", "msg_box"),
                "_id > ?", arrayOf(settings.lastMmsId.toString()), "_id ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val dateMillis = c.getLong(1) * 1000L    // MMS date는 초 단위
                    val box = c.getInt(2)

                    if (box == MSG_BOX_INBOX) {
                        val text = readText(cr, id)
                        if (text == null) {
                            // 본문이 아직 기록되지 않았다. 오래된 건이 아니면 다음 스캔에서 다시 본다.
                            if (System.currentTimeMillis() - dateMillis < PART_WAIT_MS) return
                        } else if (text.isNotEmpty()) {
                            RuleEngine.process(
                                ctx,
                                RuleInputFactory.sms(
                                    sender = readFrom(cr, id),
                                    body = text,
                                    receivedAtMillis = dateMillis,
                                    channel = "mms",
                                    dedupKey = "mms:$id",
                                ),
                            )
                        }
                    }
                    settings.lastMmsId = id
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "scan failed", e)
        }
    }

    /** text/plain 파트를 이어 붙인다. 파트가 아직 없으면 null, 텍스트가 없으면 "". */
    private fun readText(cr: ContentResolver, mmsId: Long): String? =
        cr.query(PART, arrayOf("_id", "ct", "text", "_data"), "mid = ?", arrayOf(mmsId.toString()), null)
            ?.use { c ->
                if (c.count == 0) return null
                val sb = StringBuilder()
                while (c.moveToNext()) {
                    if (c.getString(1) != "text/plain") continue
                    val partId = c.getLong(0)
                    val text = if (c.getString(3) != null) {
                        cr.openInputStream(Uri.parse("content://mms/part/$partId"))
                            ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    } else {
                        c.getString(2)
                    }
                    if (text != null) sb.append(text)
                }
                sb.toString()
            }

    private fun readFrom(cr: ContentResolver, mmsId: Long): String =
        cr.query(
            Uri.parse("content://mms/$mmsId/addr"), arrayOf("address", "type"),
            "type = ?", arrayOf(ADDR_TYPE_FROM.toString()), null
        )?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" } ?: ""
}
