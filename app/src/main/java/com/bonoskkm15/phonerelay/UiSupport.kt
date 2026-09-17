package com.bonoskkm15.phonerelay.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bonoskkm15.phonerelay.rule.RuleInput
import com.bonoskkm15.phonerelay.rule.RuleInputFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
fun StatusRow(label: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${if (ok) "✅" else "⚠️"}  $label", Modifier.weight(1f))
        if (!ok) OutlinedButton(onClick = onClick) { Text(action) }
    }
}

data class InstalledApp(val packageName: String, val label: String)

fun loadLaunchableApps(ctx: Context): List<InstalledApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return ctx.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .asSequence()
        .map { info ->
            InstalledApp(
                packageName = info.activityInfo.packageName,
                label = info.loadLabel(ctx.packageManager).toString()
                    .ifBlank { info.activityInfo.packageName },
            )
        }
        .filterNot { it.packageName == ctx.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase(Locale.getDefault()) }
        .toList()
}

fun loadRecentSmsSamples(ctx: Context, limit: Int = 50): List<RuleInput> {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
        return emptyList()
    }
    return runCatching {
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < limit) {
                    val id = cursor.getLong(0)
                    val sender = cursor.getString(1).orEmpty()
                    val body = cursor.getString(2).orEmpty()
                    val date = cursor.getLong(3)
                    if (body.isNotBlank()) add(
                        RuleInputFactory.sms(
                            sender = sender,
                            body = body,
                            receivedAtMillis = date,
                            channel = "sms",
                            dedupKey = "sample:sms:$id",
                        )
                    )
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())
}

fun formatTime(millis: Long): String =
    SimpleDateFormat("MM/dd HH:mm:ss", Locale.KOREA).format(Date(millis))

fun sourceLabel(kind: String): String = when (kind) {
    "sms" -> "문자"
    "notification" -> "앱 알림"
    else -> kind
}

fun eventLabel(rule: com.bonoskkm15.phonerelay.rule.PhoneRule): String = when {
    rule.source.kind == "sms" -> "수신 즉시"
    rule.source.events.contains("posted") -> "알림 도착"
    rule.source.events.contains("removed") -> "알림 제거"
    else -> "이벤트"
}
