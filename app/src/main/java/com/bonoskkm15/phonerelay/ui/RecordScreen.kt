package com.bonoskkm15.phonerelay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.bonoskkm15.phonerelay.core.EventQueue
import org.json.JSONObject

@Composable
fun RecordScreen(
    refreshTick: Int,
    onRefresh: () -> Unit,
    onEditRaw: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val queue = EventQueue.get(ctx)
    val stats = remember(refreshTick) { queue.stats() }
    val recent = remember(refreshTick) { queue.recent(50) }
    var detail by remember { mutableStateOf<EventQueue.Recent?>(null) }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("기록", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("대기 ${stats.pending} · 전송 ${stats.sent} · 실패 ${stats.failed}")
                Text("${com.bonoskkm15.phonerelay.core.AppSettings(ctx).lastSendStatus}", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { com.bonoskkm15.phonerelay.core.SendScheduler.sendNow(ctx); onRefresh() }) {
                        Text("지금 전송")
                    }
                    Button(onClick = {
                        queue.retryFailed()
                        com.bonoskkm15.phonerelay.core.SendScheduler.sendNow(ctx)
                        onRefresh()
                    }) { Text("실패 재시도") }
                }
            }
        }

        if (recent.isEmpty()) {
            Text("아직 기록이 없습니다.", style = MaterialTheme.typography.bodySmall)
        } else {
            recent.forEach { item ->
                Card(
                    onClick = { detail = item },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${statusMark(item.status)}  ${ruleName(item.body, item.type)}")
                        Text("${formatTime(item.createdAt)} · ${item.source}/${item.type}", style = MaterialTheme.typography.bodySmall)
                        if (item.type == "match_error") {
                            Text("형식 불일치 — 눌러서 원문 확인", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    detail?.let { item ->
        val raw = JSONObject(item.body).optJSONObject("data")?.optString("raw").orEmpty()
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("전송 JSON") },
            text = {
                SelectionContainer {
                    Text(
                        item.body,
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("PhoneRelay JSON", item.body))
                    }) { Text("복사") }
                    if (item.type == "match_error" && raw.isNotBlank()) {
                        TextButton(onClick = {
                            detail = null
                            onEditRaw(raw)
                        }) { Text("원문으로 규칙 수정") }
                    }
                    TextButton(onClick = { detail = null }) { Text("닫기") }
                }
            },
        )
    }
}

private fun statusMark(status: Int): String = when (status) {
    EventQueue.SENT -> "✅"
    EventQueue.FAILED -> "❌"
    else -> "⏳"
}

private fun ruleName(body: String, fallback: String): String = runCatching {
    JSONObject(body).optJSONObject("data")?.optString("rule_name")
        ?.takeIf { it.isNotBlank() } ?: fallback
}.getOrDefault(fallback)
