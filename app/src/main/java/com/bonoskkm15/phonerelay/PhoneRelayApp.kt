package com.bonoskkm15.phonerelay

import android.app.Application
import com.bonoskkm15.phonerelay.core.SendScheduler

class PhoneRelayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 즉시 전송이 실패해도 15분마다 남은 큐를 비운다.
        SendScheduler.schedulePeriodic(this)
    }
}
