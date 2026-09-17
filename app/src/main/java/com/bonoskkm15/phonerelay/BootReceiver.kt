package com.bonoskkm15.phonerelay.core

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import com.bonoskkm15.phonerelay.collector.cardsms.MmsScanner

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val ctx = context.applicationContext
        SendScheduler.schedulePeriodic(ctx)
        NotificationListenerService.requestRebind(ComponentName(ctx, CollectorHostService::class.java))

        val pending = goAsync()
        Thread {
            try {
                MmsScanner.scan(ctx)   // 꺼져 있던 동안 들어온 LMS 보완
                SendScheduler.sendNow(ctx)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
