package com.bonoskkm15.phonerelay.core

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SendScheduler {
    private const val WORK_NOW = "send-now"
    private const val WORK_PERIODIC = "send-periodic"

    private fun constraints(ctx: Context): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (AppSettings(ctx).wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        )
        .build()

    /** 새 이벤트가 큐에 들어가면 호출한다. */
    fun sendNow(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(constraints(ctx))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** 15분마다 남은 큐를 비운다. 설정(Wi-Fi 전용)이 바뀌면 [replace]=true로 다시 등록한다. */
    fun schedulePeriodic(ctx: Context, replace: Boolean = false) {
        val request = PeriodicWorkRequestBuilder<SendWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints(ctx))
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK_PERIODIC,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
