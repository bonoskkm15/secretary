package com.bonoskkm15.phonerelay.collector.cardsms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.bonoskkm15.phonerelay.rule.RuleEngine
import com.bonoskkm15.phonerelay.rule.RuleInputFactory

/** 단문 SMS 수신. 리시버는 ~10초 안에 끝나야 하므로 큐에 넣기만 하고 전송은 WorkManager에 맡긴다. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            ?.filterNotNull().orEmpty()
        if (messages.isEmpty()) return

        val sender = messages.first().originatingAddress.orEmpty()
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val smsTimestamp = messages.first().timestampMillis
        val ctx = context.applicationContext

        val pending = goAsync()
        Thread {
            try {
                RuleEngine.process(
                    ctx,
                    RuleInputFactory.sms(
                        sender = sender,
                        body = body,
                        receivedAtMillis = smsTimestamp.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        channel = "sms",
                        dedupKey = RuleInputFactory.smsDedupKey(sender, body, smsTimestamp),
                    ),
                )
            } finally {
                pending.finish()
            }
        }.start()
    }
}
