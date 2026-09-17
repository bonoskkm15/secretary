package com.bonoskkm15.phonerelay.rule

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import java.security.MessageDigest

/** 수집기마다 다른 입력을 규칙 엔진이 이해하는 공통 입력으로 바꾼다. */
data class RuleInput(
    val channel: String,
    val sender: String = "",
    val packageName: String = "",
    val appName: String = "",
    val title: String = "",
    val text: String = "",
    val receivedAtMillis: Long = System.currentTimeMillis(),
    val dedupKey: String,
    val action: String = RuleEvent.RECEIVED,
)

object RuleInputFactory {
    fun sms(sender: String, body: String, receivedAtMillis: Long, channel: String, dedupKey: String): RuleInput =
        RuleInput(
            channel = channel,
            sender = sender,
            text = body,
            receivedAtMillis = receivedAtMillis,
            dedupKey = dedupKey,
        )

    fun notification(ctx: Context, sbn: StatusBarNotification, action: String): RuleInput {
        val notification = sbn.notification
        val title = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = notificationText(notification)
        val appName = runCatching {
            ctx.packageManager.getApplicationLabel(
                ctx.packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrDefault(sbn.packageName)
        return RuleInput(
            channel = RuleSourceKind.NOTIFICATION,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            receivedAtMillis = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
            dedupKey = "notification:${sha256("${sbn.packageName}|${sbn.key}|$title|$text|$action")}",
            action = action,
        )
    }

    fun smsDedupKey(sender: String, body: String, timestampMillis: Long): String =
        "sms:${sha256("$sender|$timestampMillis|$body")}"

    fun notificationText(notification: Notification): String {
        val extras = notification.extras ?: return ""
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() }
            ?.takeIf { it.isNotBlank() }
        return extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: lines
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** 최근 알림 샘플. 앱 프로세스 메모리에만 두며 재시작하면 사라진다. */
object RecentRuleSamples {
    private const val LIMIT = 50
    private val notifications = ArrayDeque<RuleInput>()

    @Synchronized
    fun addNotification(input: RuleInput) {
        notifications.removeAll { it.dedupKey == input.dedupKey }
        notifications.addLast(input)
        while (notifications.size > LIMIT) notifications.removeFirst()
    }

    @Synchronized
    fun notifications(packages: Set<String> = emptySet()): List<RuleInput> =
        notifications.asReversed().filter { packages.isEmpty() || it.packageName in packages }
}
