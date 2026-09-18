package com.bonoskkm15.phonerelay.core

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bonoskkm15.phonerelay.transport.Transports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 큐의 PENDING 이벤트를 배치로 보낸다. 실패하면 WorkManager 지수 백오프로 재시도한다. */
class SendWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val settings = AppSettings(ctx)
        val queue = EventQueue.get(ctx)

        val transport = Transports.create(ctx)
        if (transport == null) {
            settings.lastSendStatus = "전송 설정이 비어 있음 (서버 주소/토큰 확인)"
            return@withContext Result.success()
        }

        var sentTotal = 0
        for (round in 1..MAX_BATCHES) {
            val batch = queue.pending()
            if (batch.isEmpty()) break
            val ids = batch.map { it.eventId }
            val accepted = try {
                transport.send(batch)
            } catch (e: Exception) {
                Log.w(TAG, "send failed", e)
                queue.markRetry(ids)
                settings.lastSendStatus = "실패: ${e.message}"
                return@withContext Result.retry()
            }
            queue.markSent(accepted)
            sentTotal += accepted.size
            val rejected = ids.filterNot { it in accepted }
            if (rejected.isNotEmpty()) {
                queue.markRetry(rejected)
                settings.lastSendStatus = "일부 실패: 성공 ${sentTotal}건, 미수신 ${rejected.size}건"
                return@withContext Result.retry()
            }
        }

        queue.prune()
        if (sentTotal > 0) settings.lastSendStatus = "성공: ${sentTotal}건 전송"
        Result.success()
    }

    companion object {
        private const val TAG = "SendWorker"
        private const val MAX_BATCHES = 20
    }
}
