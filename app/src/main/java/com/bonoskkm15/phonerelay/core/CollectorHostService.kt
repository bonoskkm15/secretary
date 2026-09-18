package com.bonoskkm15.phonerelay.core

import android.content.ComponentName
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.bonoskkm15.phonerelay.collector.cardsms.MmsScanner
import com.bonoskkm15.phonerelay.rule.RecentRuleSamples
import com.bonoskkm15.phonerelay.rule.RuleEngine
import com.bonoskkm15.phonerelay.rule.RuleEvent
import com.bonoskkm15.phonerelay.rule.RuleInputFactory

/**
 * 상주 호스트. 사용자가 "알림 접근"을 허용하면 시스템이 이 서비스를 계속 바인딩한다.
 * 포그라운드 서비스(dataSync 하루 6시간 제한) 없이 LMS 감시 옵저버를 유지하는 용도다.
 * 알림 접근 권한이 허용되면 알림 샘플을 메모리에 보관하고 규칙 엔진으로 전달한다.
 */
class CollectorHostService : NotificationListenerService() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var observer: ContentObserver? = null
    private val scanTask = Runnable { MmsScanner.scan(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("collector-host").also { it.start() }
        handler = Handler(thread.looper)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        unregisterObserver()
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                // MMS 본문(part)은 헤더보다 늦게 기록되므로 잠시 모았다가 스캔한다.
                handler.removeCallbacks(scanTask)
                handler.postDelayed(scanTask, 2_000)
            }
        }
        contentResolver.registerContentObserver(MMS_SMS_URI, true, obs)
        observer = obs
        handler.post(scanTask)
    }

    override fun onListenerDisconnected() {
        unregisterObserver()
        requestRebind(ComponentName(this, CollectorHostService::class.java))
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val value = sbn ?: return
        val input = runCatching { RuleInputFactory.notification(applicationContext, value, RuleEvent.POSTED) }
            .getOrNull() ?: return
        RecentRuleSamples.addNotification(input)
        handler.post { RuleEngine.process(applicationContext, input) }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: NotificationListenerService.RankingMap?,
        reason: Int,
    ) {
        val value = sbn ?: return
        val input = runCatching { RuleInputFactory.notification(applicationContext, value, RuleEvent.REMOVED) }
            .getOrNull() ?: return
        RecentRuleSamples.addNotification(input)
        handler.post { RuleEngine.process(applicationContext, input) }
    }

    override fun onDestroy() {
        unregisterObserver()
        thread.quitSafely()
        super.onDestroy()
    }

    private fun unregisterObserver() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        if (::handler.isInitialized) handler.removeCallbacks(scanTask)
    }

    companion object {
        private val MMS_SMS_URI: Uri = Uri.parse("content://mms-sms/")
    }
}
