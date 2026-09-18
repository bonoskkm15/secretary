package com.bonoskkm15.phonerelay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.bonoskkm15.phonerelay.rule.PhoneRule
import com.bonoskkm15.phonerelay.rule.RuleStore

@Composable
fun RuleScreen(
    refreshTick: Int,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (PhoneRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val rules = remember(refreshTick) { RuleStore.list(ctx) }
    var deleteTarget by remember { mutableStateOf<PhoneRule?>(null) }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("수집 규칙", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("원하는 출처와 필드만 레시피로 켭니다.", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onAdd) { Text("+") }
        }

        if (rules.isEmpty()) {
            Text("등록된 규칙이 없습니다.")
        } else {
            rules.forEach { rule ->
                RuleCard(
                    rule = rule,
                    onEdit = { onEdit(rule) },
                    onDuplicate = {
                        RuleStore.duplicate(ctx, rule.id)
                        onRefresh()
                    },
                    onDelete = { deleteTarget = rule },
                    onToggle = { enabled ->
                        RuleStore.setEnabled(ctx, rule.id, enabled)
                        onRefresh()
                    },
                )
            }
        }
    }

    deleteTarget?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("규칙 삭제") },
            text = { Text("‘${rule.name}’ 규칙을 삭제할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    RuleStore.remove(ctx, rule.id)
                    deleteTarget = null
                    onRefresh()
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun RuleCard(
    rule: PhoneRule,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${if (rule.enabled) "●" else "○"}  ${rule.name}",
                    Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            Text(
                "${sourceLabel(rule.source.kind)} · ${eventLabel(rule)} · ${outputSummary(rule)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "오늘 ${if (rule.matchCountDate == com.bonoskkm15.phonerelay.core.Time.nowIso().take(10)) rule.todayMatchCount else 0}건 · " +
                    "최근 ${rule.lastMatchedAt ?: "없음"}",
                style = MaterialTheme.typography.labelSmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("편집") }
                TextButton(onClick = onDuplicate) { Text("복제") }
                TextButton(onClick = onDelete) { Text("삭제") }
            }
        }
    }
}

private fun outputSummary(rule: PhoneRule): String = when {
    rule.match.rawOnly -> "원문"
    rule.output.fields.isEmpty() -> "필드 없음"
    else -> "필드 ${rule.output.fields.size}개"
}
