package com.bonoskkm15.phonerelay.collector

import android.Manifest

/** 규칙 엔진이 요구하는 출처별 권한 안내 정보. 실제 이벤트 변환은 [rule.RuleEngine]이 담당한다. */
interface Collector {
    val id: String
    val title: String
    val permissions: List<String>
}

object CollectorRegistry {
    val all: List<Collector> = listOf(
        object : Collector {
            override val id = "sms"
            override val title = "문자(SMS/LMS)"
            override val permissions = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        },
        object : Collector {
            override val id = "notification"
            override val title = "앱 알림"
            override val permissions = emptyList<String>()
        },
    )

    val allPermissions: List<String>
        get() = (all.flatMap { it.permissions } + Manifest.permission.POST_NOTIFICATIONS).distinct()
}
