package com.bonoskkm15.phonerelay.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bonoskkm15.phonerelay.collector.CollectorRegistry
import com.bonoskkm15.phonerelay.core.AppSettings
import com.bonoskkm15.phonerelay.core.EventQueue
import com.bonoskkm15.phonerelay.core.PhoneEvent
import com.bonoskkm15.phonerelay.core.SecretStore
import com.bonoskkm15.phonerelay.core.SendScheduler
import com.bonoskkm15.phonerelay.core.Time
import com.bonoskkm15.phonerelay.rule.RuleStore
import org.json.JSONObject
import java.util.UUID

@Composable
fun SettingsScreen(refreshTick: Int, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    var transport by remember(refreshTick) { mutableStateOf(settings.transport) }
    var url by remember(refreshTick) { mutableStateOf(settings.httpsUrl) }
    var token by remember { mutableStateOf("") }
    var host by remember(refreshTick) { mutableStateOf(settings.syslogHost) }
    var port by remember(refreshTick) { mutableStateOf(settings.syslogPort.toString()) }
    var deviceId by remember(refreshTick) { mutableStateOf(settings.deviceId) }
    var wifiOnly by remember(refreshTick) { mutableStateOf(settings.wifiOnly) }
    var message by remember { mutableStateOf("") }

    val runtimePerms = remember {
        CollectorRegistry.allPermissions.filter {
            it != Manifest.permission.POST_NOTIFICATIONS || Build.VERSION.SDK_INT >= 33
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onRefresh() }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            message = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(RuleStore.exportJson(ctx).toByteArray(Charsets.UTF_8)) }
                "규칙을 내보냈습니다"
            }.getOrElse { "내보내기 실패: ${it.message}" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            message = runCatching {
                val raw = ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("파일을 읽을 수 없습니다")
                "규칙 ${RuleStore.importJson(ctx, raw)}개를 가져왔습니다"
            }.getOrElse { "가져오기 실패: ${it.message}" }
            onRefresh()
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("설정", style = MaterialTheme.typography.headlineSmall)

        Section("전송") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(transport == AppSettings.TRANSPORT_HTTPS, { transport = AppSettings.TRANSPORT_HTTPS })
                Text("HTTPS + 토큰")
                RadioButton(transport == AppSettings.TRANSPORT_SYSLOG, { transport = AppSettings.TRANSPORT_SYSLOG })
                Text("syslog TCP")
            }
            if (transport == AppSettings.TRANSPORT_HTTPS) {
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("서버 주소") }, singleLine = true)
                OutlinedTextField(
                    token, { token = it }, Modifier.fillMaxWidth(),
                    label = { Text(if (SecretStore.hasToken(ctx)) "토큰 (저장됨 · 바꿀 때만 입력)" else "토큰") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            } else {
                Text("syslog는 평문이므로 내부망/VPN에서만 사용하세요.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), label = { Text("호스트") }, singleLine = true)
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(),
                    label = { Text("포트") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            OutlinedTextField(
                deviceId, { deviceId = it }, Modifier.fillMaxWidth(),
                label = { Text("기기 ID (기본값: 휴대폰 이름)") }, singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Wi-Fi에서만 전송", Modifier.weight(1f))
                Switch(wifiOnly, { wifiOnly = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val wifiChanged = wifiOnly != settings.wifiOnly
                    settings.transport = transport
                    settings.httpsUrl = url
                    settings.syslogHost = host
                    settings.syslogPort = port.toIntOrNull() ?: 514
                    settings.deviceId = deviceId
                    settings.wifiOnly = wifiOnly
                    if (token.isNotBlank()) SecretStore.saveToken(ctx, token).also { token = "" }
                    if (wifiChanged) SendScheduler.schedulePeriodic(ctx, replace = true)
                    message = "전송 설정을 저장했습니다"
                    onRefresh()
                }) { Text("저장") }
                OutlinedButton(onClick = {
                    val event = PhoneEvent(
                        source = "test",
                        type = "connection_test",
                        trigger = PhoneEvent.TRIGGER_MANUAL,
                        occurredAt = Time.nowIso(),
                        data = JSONObject().put("message", "PhoneRelay 연결 테스트"),
                    )
                    EventQueue.get(ctx).enqueue(ctx, event, "manual:connection:${UUID.randomUUID()}")
                    SendScheduler.sendNow(ctx)
                    message = "연결 테스트를 큐에 넣었습니다"
                    onRefresh()
                }) { Text("연결 테스트") }
            }
        }

        PermissionSettings(ctx, runtimePerms, permissionLauncher, refreshTick, onRefresh)

        Section("규칙 백업") {
            Text("규칙만 JSON으로 내보내고 가져옵니다. 서버 토큰은 포함하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("phonrelay-rules.json") }) { Text("내보내기") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) { Text("가져오기") }
            }
        }

        Section("사이드로드 안내") {
            Text(
                "이 앱은 개인 APK입니다. 권한이 회색이면 설정 → 애플리케이션 → 폰릴레이 → ⋮ → 제한된 설정 허용을 확인하세요.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun PermissionSettings(
    ctx: Context,
    runtimePerms: List<String>,
    permissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    refreshTick: Int,
    onRefresh: () -> Unit,
) {
    val smsOk = remember(refreshTick) { runtimePerms.filterNot { it == Manifest.permission.POST_NOTIFICATIONS }.all { granted(ctx, it) } }
    val notificationShownOk = remember(refreshTick) {
        Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    }
    val listenerOk = remember(refreshTick) {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
    val batteryOk = remember(refreshTick) {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    Section("권한 상태") {
        StatusRow("문자 수신·읽기", smsOk, "허용") { permissionLauncher.launch(runtimePerms.toTypedArray()) }
        StatusRow("알림 접근 (앱 알림/LMS)", listenerOk, "설정 열기") {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        StatusRow("알림 표시", notificationShownOk, "허용") { permissionLauncher.launch(runtimePerms.toTypedArray()) }
        StatusRow("배터리 최적화 제외", batteryOk, "제외 요청") {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${ctx.packageName}"))
            )
        }
        if (!smsOk || !listenerOk) {
            Text("권한 버튼이 회색이면 앱 정보의 ‘제한된 설정 허용’을 먼저 확인하세요.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${ctx.packageName}")))
            }) { Text("앱 정보 열기") }
        }
    }
}

private fun granted(ctx: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
