package com.bonoskkm15.phonerelay.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bonoskkm15.phonerelay.core.PhoneEvent
import com.bonoskkm15.phonerelay.core.Time
import com.bonoskkm15.phonerelay.rule.PhoneRule
import com.bonoskkm15.phonerelay.rule.RecentRuleSamples
import com.bonoskkm15.phonerelay.rule.RuleEngine
import com.bonoskkm15.phonerelay.rule.RuleEvent
import com.bonoskkm15.phonerelay.rule.RuleField
import com.bonoskkm15.phonerelay.rule.RuleFieldType
import com.bonoskkm15.phonerelay.rule.RuleFormat
import com.bonoskkm15.phonerelay.rule.RuleInput
import com.bonoskkm15.phonerelay.rule.RuleInputFactory
import com.bonoskkm15.phonerelay.rule.RuleMatch
import com.bonoskkm15.phonerelay.rule.RuleMeta
import com.bonoskkm15.phonerelay.rule.RuleOutput
import com.bonoskkm15.phonerelay.rule.RuleSource
import com.bonoskkm15.phonerelay.rule.RuleSourceKind
import com.bonoskkm15.phonerelay.rule.TemplateCompileException
import com.bonoskkm15.phonerelay.rule.TemplateCompiler
import java.util.Locale
import java.util.UUID

private const val WIZARD_STEPS = 7

@Composable
fun RuleWizardScreen(
    initialRule: PhoneRule?,
    prefillSample: String?,
    onCancel: () -> Unit,
    onSaved: (PhoneRule) -> Unit,
) {
    val ctx = LocalContext.current
    val apps = remember { loadLaunchableApps(ctx) }
    val key = initialRule?.id ?: "new"
    val initialSample = prefillSample ?: initialRule?.sample.orEmpty()

    var step by remember(key, prefillSample) { mutableIntStateOf(0) }
    var name by remember(key, prefillSample) { mutableStateOf(initialRule?.name.orEmpty()) }
    var type by remember(key, prefillSample) { mutableStateOf(initialRule?.type.orEmpty()) }
    var sourceKind by remember(key, prefillSample) {
        mutableStateOf(initialRule?.source?.kind ?: RuleSourceKind.SMS)
    }
    var senders by remember(key, prefillSample) {
        mutableStateOf(initialRule?.source?.senders?.joinToString(", ").orEmpty())
    }
    var selectedPackages by remember(key, prefillSample) {
        mutableStateOf(
            initialRule?.source?.packages.orEmpty().map { pkg ->
                apps.firstOrNull { it.packageName == pkg } ?: InstalledApp(pkg, pkg)
            }
        )
    }
    var sourceEvents by remember(key, prefillSample) {
        mutableStateOf(initialRule?.source?.events?.toSet() ?: setOf(RuleEvent.RECEIVED))
    }
    var notificationText by remember(key, prefillSample) {
        mutableStateOf(initialRule?.source?.notificationText ?: RuleSource.NOTIFICATION_TITLE_AND_TEXT)
    }
    var sample by remember(key, prefillSample) { mutableStateOf(initialSample) }
    var template by remember(key, prefillSample) {
        mutableStateOf(TextFieldValue(initialRule?.template ?: initialSample))
    }
    var fields by remember(key, prefillSample) { mutableStateOf(initialRule?.fields.orEmpty()) }
    var includeText by remember(key, prefillSample) {
        mutableStateOf(initialRule?.match?.include?.joinToString(", ").orEmpty())
    }
    var excludeText by remember(key, prefillSample) {
        mutableStateOf(initialRule?.match?.exclude?.joinToString(", ") ?: "인증번호")
    }
    var matchMode by remember(key, prefillSample) {
        mutableStateOf(initialRule?.match?.mode ?: RuleMatch.MODE_FULL)
    }
    var rawOnly by remember(key, prefillSample) { mutableStateOf(initialRule?.match?.rawOnly ?: false) }
    var formats by remember(key, prefillSample) { mutableStateOf(initialRule?.match?.formats.orEmpty()) }
    var reportMismatch by remember(key, prefillSample) {
        mutableStateOf(initialRule?.match?.reportMismatch ?: true)
    }
    var masks by remember(key, prefillSample) {
        mutableStateOf(initialRule?.match?.maskPatterns?.joinToString("\n") ?: "(?m)^고객명 .*$")
    }
    var outputFields by remember(key, prefillSample) {
        mutableStateOf(initialRule?.output?.fields?.toSet() ?: emptySet())
    }
    var occurredAtField by remember(key, prefillSample) {
        mutableStateOf(initialRule?.output?.occurredAtField.orEmpty())
    }
    var receivedAtMeta by remember(key, prefillSample) {
        mutableStateOf(initialRule?.output?.meta?.receivedAt ?: true)
    }
    var channelMeta by remember(key, prefillSample) {
        mutableStateOf(initialRule?.output?.meta?.channel ?: true)
    }
    var senderMeta by remember(key, prefillSample) {
        mutableStateOf(initialRule?.output?.meta?.sender ?: false)
    }
    var senderMask by remember(key, prefillSample) {
        mutableStateOf(initialRule?.output?.meta?.senderMask ?: true)
    }
    var appMeta by remember(key, prefillSample) {
        mutableStateOf(initialRule?.output?.meta?.app ?: sourceKind == RuleSourceKind.NOTIFICATION)
    }
    var includeRaw by remember(key, prefillSample) {
        mutableStateOf(initialRule?.output?.includeRaw ?: false)
    }
    var error by remember { mutableStateOf("") }
    var appSearch by remember { mutableStateOf("") }
    var showSamplePicker by remember { mutableStateOf(false) }
    var showFieldEditor by remember { mutableStateOf(false) }
    var editingField by remember { mutableStateOf<RuleField?>(null) }
    var insertRange by remember { mutableStateOf<TextRange?>(null) }
    var suggestedFieldText by remember { mutableStateOf("") }
    var showFormatEditor by remember { mutableStateOf(false) }
    var editingFormat by remember { mutableStateOf<RuleFormat?>(null) }
    var draftFormat by remember { mutableStateOf<RuleFormat?>(null) }
    var showFormatPicker by remember { mutableStateOf(false) }

    val ruleId = remember(key) { initialRule?.id ?: UUID.randomUUID().toString() }
    val createdAt = remember(key) { initialRule?.createdAt ?: Time.nowIso() }

    fun makeRule(): PhoneRule {
        val now = Time.nowIso()
        val cleanSenders = senders.split(',', '\n').map { it.filter(Char::isDigit) }.filter(String::isNotEmpty)
        val cleanPackages = selectedPackages.map { it.packageName }.distinct()
        val cleanFields = fields.filter { it.key.isNotBlank() }
        val finalOutputFields = if (rawOnly) listOf("text") else outputFields.filter { key -> cleanFields.any { it.key == key && it.type != RuleFieldType.IGNORE } }
        return PhoneRule(
            id = ruleId,
            name = name.trim(),
            type = type.trim().ifEmpty { PhoneRule.slug(name) },
            enabled = initialRule?.enabled ?: true,
            source = RuleSource(
                kind = sourceKind,
                senders = if (sourceKind == RuleSourceKind.SMS) cleanSenders else emptyList(),
                packages = if (sourceKind == RuleSourceKind.NOTIFICATION) cleanPackages else emptyList(),
                events = sourceEvents.toList().ifEmpty {
                    if (sourceKind == RuleSourceKind.SMS) listOf(RuleEvent.RECEIVED) else listOf(RuleEvent.POSTED)
                },
                notificationText = notificationText,
            ),
            sample = sample,
            template = template.text,
            fields = cleanFields,
            match = RuleMatch(
                mode = matchMode,
                include = includeText.split(',', '\n').map(String::trim).filter(String::isNotEmpty),
                exclude = excludeText.split(',', '\n').map(String::trim).filter(String::isNotEmpty),
                rawOnly = rawOnly,
                reportMismatch = reportMismatch,
                maskPatterns = masks.lines().map(String::trim).filter(String::isNotEmpty),
                formats = if (rawOnly) formats else emptyList(),
            ),
            output = RuleOutput(
                fields = finalOutputFields,
                occurredAtField = occurredAtField.trim().ifEmpty { null },
                meta = RuleMeta(receivedAtMeta, channelMeta, senderMeta, senderMask, appMeta),
                includeRaw = includeRaw,
            ),
            createdAt = createdAt,
            updatedAt = now,
            lastMatchedAt = initialRule?.lastMatchedAt,
            todayMatchCount = initialRule?.todayMatchCount ?: 0,
            matchCountDate = initialRule?.matchCountDate,
        )
    }

    fun sampleInput(): RuleInput {
        val now = System.currentTimeMillis()
        return if (sourceKind == RuleSourceKind.SMS) {
            RuleInputFactory.sms(
                sender = senders.split(',', '\n').firstOrNull()?.trim().orEmpty(),
                body = sample,
                receivedAtMillis = now,
                channel = "sms",
                dedupKey = "preview:${UUID.randomUUID()}",
            )
        } else {
            val app = selectedPackages.firstOrNull()
            RuleInput(
                channel = RuleSourceKind.NOTIFICATION,
                packageName = app?.packageName.orEmpty(),
                appName = app?.label.orEmpty(),
                title = if (notificationText == RuleSource.NOTIFICATION_TITLE) sample else "",
                text = if (notificationText == RuleSource.NOTIFICATION_TITLE) "" else sample,
                receivedAtMillis = now,
                dedupKey = "preview:${UUID.randomUUID()}",
                action = sourceEvents.firstOrNull { it == RuleEvent.POSTED || it == RuleEvent.REMOVED } ?: RuleEvent.POSTED,
            )
        }
    }

    fun validate(targetStep: Int): String? = when (targetStep) {
        0 -> if (name.trim().isEmpty()) "규칙 이름을 입력하세요" else null
        1 -> when {
            sourceKind == RuleSourceKind.NOTIFICATION && selectedPackages.isEmpty() -> "모니터링할 앱을 하나 이상 선택하세요"
            sourceEvents.isEmpty() -> "잡을 행위를 하나 이상 선택하세요"
            else -> null
        }
        2 -> if (sample.trim().isEmpty()) "샘플 원문을 입력하거나 최근 항목에서 선택하세요" else null
        3 -> if (!rawOnly) {
            try {
                TemplateCompiler.compile(template.text, fields)
                null
            } catch (e: TemplateCompileException) {
                e.message ?: "템플릿을 확인하세요"
            }
        } else null
        5 -> if (!rawOnly && outputFields.none { key -> fields.any { it.key == key && it.type != RuleFieldType.IGNORE } }) {
            "전송할 필드를 하나 이상 선택하세요"
        } else null
        else -> null
    }

    // 시스템 뒤로가기: 이전 단계로. 첫 단계에서는 마법사를 닫는다.
    // (없으면 마법사가 Scaffold 밖 화면이라 액티비티가 그대로 종료된다)
    BackHandler {
        if (step > 0) {
            step--
            error = ""
        } else {
            onCancel()
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("‹ 뒤로") }
            Column(Modifier.weight(1f)) {
                Text("규칙 만들기", style = MaterialTheme.typography.titleLarge)
                Text("${step + 1}/$WIZARD_STEPS  ${wizardTitle(step)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                0 -> NameStep(name, { name = it }, type, { type = it })
                1 -> SourceStep(
                    sourceKind = sourceKind,
                    onSourceKind = {
                        sourceKind = it
                        appMeta = it == RuleSourceKind.NOTIFICATION
                        if (it == RuleSourceKind.SMS) sourceEvents = setOf(RuleEvent.RECEIVED)
                        else sourceEvents = setOf(RuleEvent.POSTED)
                    },
                    senders = senders,
                    onSenders = { senders = it },
                    apps = apps,
                    selectedPackages = selectedPackages,
                    onPackages = { selectedPackages = it },
                    search = appSearch,
                    onSearch = { appSearch = it },
                    events = sourceEvents,
                    onEvents = { sourceEvents = it },
                    notificationText = notificationText,
                    onNotificationText = { notificationText = it },
                )
                2 -> SampleStep(
                    sourceKind = sourceKind,
                    sample = sample,
                    onSample = { sample = it; if (template.text.isBlank()) template = TextFieldValue(it) },
                    onPick = { showSamplePicker = true },
                )
                3 -> if (rawOnly) RawFormatStep(
                    rawOnly = rawOnly,
                    onRawOnly = { rawOnly = it },
                    formats = formats,
                    sampleNormalized = TemplateCompiler.normalize(sample),
                    onAddFromSample = {
                        editingFormat = null
                        draftFormat = RuleFormat.suggestFrom(sample)
                        showFormatEditor = true
                    },
                    onAddFromRecent = { showFormatPicker = true },
                    onAddManual = {
                        editingFormat = null
                        draftFormat = null
                        showFormatEditor = true
                    },
                    onEditFormat = {
                        editingFormat = it
                        draftFormat = it
                        showFormatEditor = true
                    },
                    onDeleteFormat = { target -> formats = formats.filterNot { it == target } },
                ) else TemplateStep(
                    rawOnly = rawOnly,
                    onRawOnly = { rawOnly = it },
                    template = template,
                    onTemplate = { template = it },
                    fields = fields,
                    onAddField = {
                        val selection = template.selection
                        val start = minOf(selection.start, selection.end)
                        val end = maxOf(selection.start, selection.end)
                        insertRange = TextRange(start, end)
                        suggestedFieldText = template.text.substring(start, end)
                        editingField = null
                        showFieldEditor = true
                    },
                    onEditField = {
                        insertRange = null
                        suggestedFieldText = ""
                        editingField = it
                        showFieldEditor = true
                    },
                    onDeleteField = { field ->
                        fields = fields.filterNot { it.key == field.key }
                        outputFields = outputFields - field.key
                    },
                    onOutputFields = { outputFields = it },
                )
                4 -> ConditionStep(
                    includeText, { includeText = it },
                    excludeText, { excludeText = it },
                    matchMode, { matchMode = it },
                    rawOnly,
                    reportMismatch, { reportMismatch = it },
                    masks, { masks = it },
                )
                5 -> OutputStep(
                    fields = fields,
                    rawOnly = rawOnly,
                    outputFields = outputFields,
                    onOutputFields = { outputFields = it },
                    receivedAt = receivedAtMeta,
                    onReceivedAt = { receivedAtMeta = it },
                    channel = channelMeta,
                    onChannel = { channelMeta = it },
                    sender = senderMeta,
                    onSender = { senderMeta = it },
                    senderMask = senderMask,
                    onSenderMask = { senderMask = it },
                    app = appMeta,
                    onApp = { appMeta = it },
                    includeRaw = includeRaw,
                    onIncludeRaw = { includeRaw = it },
                    occurredAtField = occurredAtField,
                    onOccurredAtField = { occurredAtField = it },
                )
                6 -> PreviewStep(
                    ctx = ctx,
                    rule = makeRule(),
                    input = sampleInput(),
                    onTestSend = { rule, input ->
                        val sent = RuleEngine.sendPreview(ctx, rule, input)
                        error = if (sent) "샘플을 전송 큐에 넣었습니다" else "미리보기 매칭에 실패했거나 이미 전송한 샘플입니다"
                    },
                )
            }
            if (error.isNotBlank()) Text(error, color = if (error.contains("실패")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(enabled = step > 0, onClick = { step--; error = "" }) { Text("이전") }
            if (step < WIZARD_STEPS - 1) {
                Button(onClick = {
                    val issue = validate(step)
                    if (issue == null) {
                        step++
                        error = ""
                    } else error = issue
                }) { Text("다음") }
            } else {
                Button(onClick = {
                    val rule = makeRule()
                    val preview = RuleEngine.preview(rule, sampleInput())
                    if (preview.matched || rawOnly) onSaved(rule) else error = "미리보기 매칭에 실패했습니다. 템플릿과 샘플을 확인하세요."
                }) { Text("저장") }
            }
        }
    }

    if (showSamplePicker) {
        val isSms = sourceKind == RuleSourceKind.SMS
        // 권한이 없어서 비어 있는 것과 아직 아무것도 안 쌓인 것을 구분해서 안내한다.
        val permissionGranted = if (isSms) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        }
        val samples = if (isSms) loadRecentSmsSamples(ctx) else {
            RecentRuleSamples.notifications(selectedPackages.map { it.packageName }.toSet())
        }
        SamplePickerDialog(
            samples = samples,
            isSms = isSms,
            permissionGranted = permissionGranted,
            onOpenSettings = {
                val intent = if (isSms) {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:" + ctx.packageName))
                } else {
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                }
                runCatching { ctx.startActivity(intent) }
            },
            onDismiss = { showSamplePicker = false },
        ) { picked ->
            sample = picked.textForWizard()
            template = TextFieldValue(sample)
            showSamplePicker = false
        }
    }

    if (showFormatEditor) {
        FormatEditorDialog(
            initial = draftFormat,
            isEdit = editingFormat != null,
            sampleNormalized = TemplateCompiler.normalize(sample),
            onDismiss = { showFormatEditor = false },
            onSave = { format ->
                val old = editingFormat
                formats = if (old == null) formats + format else formats.map { if (it == old) format else it }
                editingFormat = null
                draftFormat = null
                showFormatEditor = false
            },
        )
    }

    if (showFormatPicker) {
        val isSms = sourceKind == RuleSourceKind.SMS
        val senderFilter = senders.split(',', '\n').map { it.filter(Char::isDigit) }.filter(String::isNotEmpty).toSet()
        val recent = if (isSms) {
            loadRecentSmsSamples(ctx).filter { senderFilter.isEmpty() || it.sender.filter(Char::isDigit) in senderFilter }
        } else {
            RecentRuleSamples.notifications(selectedPackages.map { it.packageName }.toSet())
        }
        FormatPickerDialog(
            samples = recent.map { it.textForWizard() },
            existing = formats,
            isSms = isSms,
            onDismiss = { showFormatPicker = false },
            onAdd = { picked ->
                formats = formats + picked.filter { p -> formats.none { it.kind == p.kind && it.value == p.value } }
                showFormatPicker = false
            },
        )
    }

    if (showFieldEditor) {
        FieldEditorDialog(
            initial = editingField,
            suggestedText = suggestedFieldText,
            onDismiss = { showFieldEditor = false },
            onSave = { field ->
                val oldKey = editingField?.key
                fields = if (oldKey == null) {
                    fields.filterNot { it.key == field.key } + field
                } else {
                    fields.map { if (it.key == oldKey) field else it }
                }
                if (oldKey != null && oldKey != field.key) outputFields = outputFields - oldKey
                if (!rawOnly && outputFields.isEmpty() && field.type != RuleFieldType.IGNORE) outputFields = setOf(field.key)
                insertRange?.let { range ->
                    val placeholder = placeholderFor(field)
                    val textValue = template.text
                    val replacement = textValue.substring(0, range.start) + placeholder + textValue.substring(range.end)
                    val cursor = range.start + placeholder.length
                    template = TextFieldValue(replacement, TextRange(cursor))
                }
                insertRange = null
                suggestedFieldText = ""
                editingField = null
                showFieldEditor = false
            },
        )
    }
}

@Composable
private fun NameStep(name: String, onName: (String) -> Unit, type: String, onType: (String) -> Unit) {
    Text("① 이름", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(name, onName, Modifier.fillMaxWidth(), label = { Text("규칙 이름") }, singleLine = true)
    OutlinedTextField(
        type,
        onType,
        Modifier.fillMaxWidth(),
        label = { Text("type (영문 snake_case)") },
        placeholder = { Text("예: message_received") },
        singleLine = true,
    )
    Text("type을 비워두면 이름에서 자동으로 제안합니다.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SourceStep(
    sourceKind: String,
    onSourceKind: (String) -> Unit,
    senders: String,
    onSenders: (String) -> Unit,
    apps: List<InstalledApp>,
    selectedPackages: List<InstalledApp>,
    onPackages: (List<InstalledApp>) -> Unit,
    search: String,
    onSearch: (String) -> Unit,
    events: Set<String>,
    onEvents: (Set<String>) -> Unit,
    notificationText: String,
    onNotificationText: (String) -> Unit,
) {
    Text("② 어디서", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(sourceKind == RuleSourceKind.SMS, { onSourceKind(RuleSourceKind.SMS) })
        Text("문자(SMS/LMS)")
        RadioButton(sourceKind == RuleSourceKind.NOTIFICATION, { onSourceKind(RuleSourceKind.NOTIFICATION) })
        Text("앱 알림")
    }
    if (sourceKind == RuleSourceKind.SMS) {
        OutlinedTextField(
            senders,
            onSenders,
            Modifier.fillMaxWidth(),
            label = { Text("발신번호 필터 (선택, 쉼표 구분)") },
            placeholder = { Text("비워두면 모든 발신번호") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
    } else {
        OutlinedTextField(search, onSearch, Modifier.fillMaxWidth(), label = { Text("앱 검색") }, singleLine = true)
        val q = search.trim().lowercase(Locale.getDefault())
        val filtered = apps.filter { q.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(q) || it.packageName.contains(q) }
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            filtered.take(100).forEach { app ->
                WizardCheckRow(
                    "${app.label} (${app.packageName})",
                    app in selectedPackages,
                ) {
                    onPackages(if (app in selectedPackages) selectedPackages - app else selectedPackages + app)
                }
            }
        }
        Text("③ 잡을 행위", style = MaterialTheme.typography.titleSmall)
        WizardCheckRow("알림 도착", RuleEvent.POSTED in events) { onEvents(events.toggle(RuleEvent.POSTED)) }
        WizardCheckRow("알림 제거", RuleEvent.REMOVED in events) { onEvents(events.toggle(RuleEvent.REMOVED)) }
        Text("알림 원문 구성", style = MaterialTheme.typography.titleSmall)
        RadioText("제목", notificationText == RuleSource.NOTIFICATION_TITLE) { onNotificationText(RuleSource.NOTIFICATION_TITLE) }
        RadioText("내용", notificationText == RuleSource.NOTIFICATION_TEXT) { onNotificationText(RuleSource.NOTIFICATION_TEXT) }
        RadioText("제목 + 내용", notificationText == RuleSource.NOTIFICATION_TITLE_AND_TEXT) { onNotificationText(RuleSource.NOTIFICATION_TITLE_AND_TEXT) }
    }
    Text("통화·위치·기기 상태는 다음 단계에서 추가됩니다.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SampleStep(sourceKind: String, sample: String, onSample: (String) -> Unit, onPick: () -> Unit) {
    Text("③ 샘플", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPick) { Text(if (sourceKind == RuleSourceKind.SMS) "최근 문자에서 고르기" else "최근 알림에서 고르기") }
    }
    OutlinedTextField(
        sample,
        onSample,
        Modifier.fillMaxWidth(),
        label = { Text("샘플 원문 (직접 붙여넣기 가능)") },
        minLines = 8,
    )
    Text("샘플은 규칙에 저장되어 나중에 편집·미리보기에 사용됩니다.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TemplateStep(
    rawOnly: Boolean,
    onRawOnly: (Boolean) -> Unit,
    template: TextFieldValue,
    onTemplate: (TextFieldValue) -> Unit,
    fields: List<RuleField>,
    onAddField: () -> Unit,
    onEditField: (RuleField) -> Unit,
    onDeleteField: (RuleField) -> Unit,
    onOutputFields: (Set<String>) -> Unit,
) {
    Text("④ 포맷 지정", style = MaterialTheme.typography.titleMedium)
    RawOnlySwitch(rawOnly, onRawOnly)
    OutlinedTextField(
        value = template,
        onValueChange = onTemplate,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("템플릿") },
        minLines = 8,
    )
    Text("본문에서 일부를 드래그해 선택한 뒤 아래 버튼을 누르면 자리표시자로 바뀝니다.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = onAddField, Modifier.fillMaxWidth()) { Text("선택 영역을 필드로 지정 / 필드 추가") }
    if (fields.isEmpty()) Text("아직 필드가 없습니다.", style = MaterialTheme.typography.bodySmall)
    fields.forEach { field ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${field.label}  {${field.key}}")
                Text(fieldDescription(field), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { onEditField(field) }) { Text("편집") }
            TextButton(onClick = { onDeleteField(field) }) { Text("삭제") }
        }
    }
}

@Composable
private fun ConditionStep(
    include: String, onInclude: (String) -> Unit,
    exclude: String, onExclude: (String) -> Unit,
    mode: String, onMode: (String) -> Unit,
    rawOnly: Boolean,
    reportMismatch: Boolean, onReportMismatch: (Boolean) -> Unit,
    masks: String, onMasks: (String) -> Unit,
) {
    Text("⑤ 조건·옵션", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(include, onInclude, Modifier.fillMaxWidth(), label = { Text("포함 키워드 (쉼표/줄바꿈)") }, minLines = 2)
    OutlinedTextField(exclude, onExclude, Modifier.fillMaxWidth(), label = { Text("제외 키워드 (기본: 인증번호)") }, minLines = 2)
    if (!rawOnly) {
        Text("매칭 방식")
        RadioText("전체 일치", mode == RuleMatch.MODE_FULL) { onMode(RuleMatch.MODE_FULL) }
        RadioText("부분 일치 허용", mode == RuleMatch.MODE_FIND) { onMode(RuleMatch.MODE_FIND) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("포맷 불일치 이벤트 보고", Modifier.weight(1f))
            Switch(reportMismatch, onReportMismatch)
        }
    }
    OutlinedTextField(masks, onMasks, Modifier.fillMaxWidth(), label = { Text("원문 마스킹 정규식 (한 줄 하나)") }, minLines = 3)
}

@Composable
private fun OutputStep(
    fields: List<RuleField>, rawOnly: Boolean, outputFields: Set<String>, onOutputFields: (Set<String>) -> Unit,
    receivedAt: Boolean, onReceivedAt: (Boolean) -> Unit,
    channel: Boolean, onChannel: (Boolean) -> Unit,
    sender: Boolean, onSender: (Boolean) -> Unit,
    senderMask: Boolean, onSenderMask: (Boolean) -> Unit,
    app: Boolean, onApp: (Boolean) -> Unit,
    includeRaw: Boolean, onIncludeRaw: (Boolean) -> Unit,
    occurredAtField: String, onOccurredAtField: (String) -> Unit,
) {
    Text("⑥ 보낼 항목", style = MaterialTheme.typography.titleMedium)
    if (rawOnly) {
        WizardCheckRow("원문 text", true) {}
    } else {
        fields.filter { it.type != RuleFieldType.IGNORE }.forEach { field ->
            WizardCheckRow("${field.label} (${field.key})", field.key in outputFields) {
                onOutputFields(outputFields.toggle(field.key))
            }
        }
        if (fields.none { it.type != RuleFieldType.IGNORE }) Text("먼저 포맷 단계에서 필드를 지정하세요.", style = MaterialTheme.typography.bodySmall)
    }
    Text("메타데이터", style = MaterialTheme.typography.titleSmall)
    WizardCheckRow("수신 시각", receivedAt) { onReceivedAt(!receivedAt) }
    WizardCheckRow("출처 채널", channel) { onChannel(!channel) }
    WizardCheckRow("발신번호", sender) { onSender(!sender) }
    if (sender) WizardCheckRow("발신번호 마스킹", senderMask) { onSenderMask(!senderMask) }
    WizardCheckRow("앱 이름·패키지", app) { onApp(!app) }
    WizardCheckRow("원문 포함", includeRaw) { onIncludeRaw(!includeRaw) }
    OutlinedTextField(occurredAtField, onOccurredAtField, Modifier.fillMaxWidth(), label = { Text("발생 시각 필드 (비우면 수신 시각)") }, singleLine = true)
    fields.filter { it.type == RuleFieldType.DATETIME }.forEach { field ->
        TextButton(onClick = { onOccurredAtField(field.key) }) { Text("발생 시각으로 ${field.key} 사용") }
    }
    TextButton(onClick = { onOccurredAtField("") }) { Text("수신 시각 사용") }
}

@Composable
private fun RawOnlySwitch(rawOnly: Boolean, onRawOnly: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("필드 추출 없이 원문 전송")
            Text("메시지 전문을 그대로 보내고, 필드 추출은 서버에서 합니다.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(rawOnly, onRawOnly)
    }
    HorizontalDivider()
}

@Composable
private fun RawFormatStep(
    rawOnly: Boolean,
    onRawOnly: (Boolean) -> Unit,
    formats: List<RuleFormat>,
    sampleNormalized: String,
    onAddFromSample: () -> Unit,
    onAddFromRecent: () -> Unit,
    onAddManual: () -> Unit,
    onEditFormat: (RuleFormat) -> Unit,
    onDeleteFormat: (RuleFormat) -> Unit,
) {
    Text("④ 포맷 지정", style = MaterialTheme.typography.titleMedium)
    RawOnlySwitch(rawOnly, onRawOnly)
    Text("보낼 포맷", style = MaterialTheme.typography.titleSmall)
    Text(
        if (formats.isEmpty()) "비워두면 이 출처의 모든 메시지를 보냅니다. 포맷을 추가하면 그 포맷에 맞는 메시지만 보냅니다."
        else "아래 포맷 중 하나라도 맞는 메시지만 보냅니다. 맞은 포맷 이름은 JSON의 format 값으로 들어갑니다.",
        style = MaterialTheme.typography.bodySmall,
    )
    formats.forEach { format ->
        val hit = sampleNormalized.isNotEmpty() && format.matches(sampleNormalized)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(format.name + if (hit) "  ✓ 현재 샘플" else "")
                Text(format.describe(), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { onEditFormat(format) }) { Text("편집") }
            TextButton(onClick = { onDeleteFormat(format) }) { Text("삭제") }
        }
    }
    OutlinedButton(onClick = onAddFromRecent, Modifier.fillMaxWidth()) { Text("최근 메시지에서 포맷 고르기") }
    OutlinedButton(onClick = onAddFromSample, Modifier.fillMaxWidth(), enabled = sampleNormalized.isNotEmpty()) { Text("현재 샘플로 포맷 추가") }
    TextButton(onClick = onAddManual, Modifier.fillMaxWidth()) { Text("직접 입력") }
}

@Composable
private fun FormatEditorDialog(
    initial: RuleFormat?,
    isEdit: Boolean,
    sampleNormalized: String,
    onDismiss: () -> Unit,
    onSave: (RuleFormat) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var kind by remember(initial) { mutableStateOf(initial?.kind ?: RuleFormat.KIND_STARTS_WITH) }
    var value by remember(initial) { mutableStateOf(initial?.value.orEmpty()) }
    var error by remember { mutableStateOf("") }
    val draft = RuleFormat(name.trim().ifEmpty { value.trim() }, kind, value.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "포맷 편집" else "포맷 추가") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("포맷 이름 (JSON format 값)") }, placeholder = { Text("예: 출금") }, singleLine = true)
                Text("조건")
                RadioText("첫 줄이 이 문구로 시작", kind == RuleFormat.KIND_STARTS_WITH) { kind = RuleFormat.KIND_STARTS_WITH }
                RadioText("이 문구를 포함", kind == RuleFormat.KIND_CONTAINS) { kind = RuleFormat.KIND_CONTAINS }
                RadioText("정규식", kind == RuleFormat.KIND_REGEX) { kind = RuleFormat.KIND_REGEX }
                OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text(if (kind == RuleFormat.KIND_REGEX) "정규식" else "문구") }, singleLine = true)
                if (sampleNormalized.isNotEmpty() && draft.value.isNotEmpty()) {
                    val hit = draft.matches(sampleNormalized)
                    Text(
                        if (hit) "현재 샘플: 맞음" else "현재 샘플: 안 맞음",
                        color = if (hit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    draft.value.isEmpty() -> error = "문구를 입력하세요"
                    kind == RuleFormat.KIND_REGEX && runCatching { Regex(draft.value) }.isFailure -> error = "정규식이 올바르지 않습니다"
                    else -> onSave(draft)
                }
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 최근 메시지를 시작 문구별로 묶어 보여주고, 체크한 것들을 포맷으로 한 번에 추가한다. */
@Composable
private fun FormatPickerDialog(
    samples: List<String>,
    existing: List<RuleFormat>,
    isSms: Boolean,
    onDismiss: () -> Unit,
    onAdd: (List<RuleFormat>) -> Unit,
) {
    val groups = remember(samples, existing) {
        samples.mapNotNull { text -> RuleFormat.suggestFrom(text)?.let { it to text } }
            .groupBy { it.first.value }
            .map { (_, items) -> Triple(items.first().first, items.size, items.first().second) }
            .filter { (format, _, _) -> existing.none { it.kind == format.kind && it.value == format.value } }
    }
    var checked by remember(groups) { mutableStateOf(emptySet<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("포맷 고르기") },
        text = {
            if (groups.isEmpty()) Text(
                if (samples.isEmpty()) {
                    if (isSms) "최근 문자가 없거나 문자 읽기 권한이 없습니다." else "아직 수집된 알림이 없습니다. 알림 접근을 켠 뒤 도착한 알림부터 보입니다."
                } else "새로 추가할 포맷이 없습니다."
            )
            else Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("보낼 포맷을 체크하세요. 첫 줄의 시작 문구로 묶었습니다.", style = MaterialTheme.typography.bodySmall)
                groups.forEach { (format, count, example) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(format.value in checked, { checked = checked.toggle(format.value) })
                        Column(Modifier.weight(1f)) {
                            Text("${format.name}  (${count}건)")
                            Text(example.replace('\n', ' ').take(60), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = checked.isNotEmpty(),
                onClick = { onAdd(groups.map { it.first }.filter { it.value in checked }) },
            ) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun PreviewStep(
    ctx: android.content.Context,
    rule: PhoneRule,
    input: RuleInput,
    onTestSend: (PhoneRule, RuleInput) -> Unit,
) {
    Text("⑦ 미리보기", style = MaterialTheme.typography.titleMedium)
    val preview = RuleEngine.preview(rule, input)
    if (!preview.matched && rule.match.rawOnly) {
        Text(
            "이 샘플은 전송 대상이 아닙니다: ${preview.error ?: "포함/제외 키워드에 걸렸습니다"}",
            color = MaterialTheme.colorScheme.error,
        )
        Text("규칙은 저장할 수 있습니다. 전송될 메시지로 확인하려면 샘플 단계에서 다른 샘플을 고르세요.", style = MaterialTheme.typography.bodySmall)
    } else if (!preview.matched) {
        Text("매칭 실패: ${preview.error ?: "조건 또는 템플릿을 확인하세요"}", color = MaterialTheme.colorScheme.error)
        preview.failedLine?.let { Text("첫 실패 줄: $it", color = MaterialTheme.colorScheme.error) }
    } else {
        preview.format?.let { Text("맞은 포맷: $it", style = MaterialTheme.typography.titleSmall) }
        Text("추출 필드", style = MaterialTheme.typography.titleSmall)
        preview.fields.forEach { (key, value) -> Text("$key  =  $value") }
        HorizontalDivider()
        Text("실제 전송 JSON", style = MaterialTheme.typography.titleSmall)
        Text(
            RuleEngine.previewJson(ctx, rule, input),
            Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
        )
        Button(onClick = { onTestSend(rule, input) }, Modifier.fillMaxWidth()) { Text("샘플로 테스트 전송") }
    }
}

@Composable
private fun FieldEditorDialog(
    initial: RuleField?,
    suggestedText: String,
    onDismiss: () -> Unit,
    onSave: (RuleField) -> Unit,
) {
    var label by remember(initial, suggestedText) { mutableStateOf(initial?.label ?: suggestedText.trim().take(20)) }
    var fieldKey by remember(initial, suggestedText) {
        mutableStateOf(initial?.key ?: suggestedKey(suggestedText))
    }
    var type by remember(initial) { mutableStateOf(initial?.type ?: RuleFieldType.TEXT) }
    var format by remember(initial) { mutableStateOf(initial?.format.orEmpty()) }
    var mapping by remember(initial) {
        mutableStateOf(initial?.mapping?.entries?.joinToString("\n") { "${it.key}=${it.value}" }.orEmpty())
    }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "필드 지정" else "필드 편집") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (suggestedText.isNotBlank()) Text("선택한 값: $suggestedText", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), label = { Text("표시 이름") }, singleLine = true)
                OutlinedTextField(fieldKey, { fieldKey = it }, Modifier.fillMaxWidth(), label = { Text("JSON 키") }, singleLine = true)
                Text("타입")
                RadioText("텍스트", type == RuleFieldType.TEXT) { type = RuleFieldType.TEXT }
                RadioText("숫자", type == RuleFieldType.NUMBER) { type = RuleFieldType.NUMBER }
                RadioText("날짜·시간", type == RuleFieldType.DATETIME) { type = RuleFieldType.DATETIME }
                RadioText("무시(개인정보 등)", type == RuleFieldType.IGNORE) { type = RuleFieldType.IGNORE }
                if (type == RuleFieldType.DATETIME) {
                    OutlinedTextField(format, { format = it }, Modifier.fillMaxWidth(), label = { Text("날짜 형식") }, placeholder = { Text("MM/dd HH:mm") }, singleLine = true)
                }
                if (type == RuleFieldType.TEXT) {
                    OutlinedTextField(mapping, { mapping = it }, Modifier.fillMaxWidth(), label = { Text("값 매핑 (원문=JSON값)") }, minLines = 2)
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cleanKey = fieldKey.trim()
                when {
                    !cleanKey.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) -> error = "JSON 키는 영문·숫자·밑줄만 사용할 수 있습니다"
                    type == RuleFieldType.DATETIME && format.trim().isEmpty() -> error = "날짜 형식을 입력하세요"
                    else -> {
                        val map = mapping.lines().mapNotNull { line ->
                            val pos = line.indexOf('=')
                            if (pos <= 0) null else line.substring(0, pos).trim() to line.substring(pos + 1).trim()
                        }.toMap()
                        onSave(RuleField(cleanKey, label.trim().ifEmpty { cleanKey }, type, format.trim().ifEmpty { null }, map))
                    }
                }
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun SamplePickerDialog(
    samples: List<RuleInput>,
    isSms: Boolean,
    permissionGranted: Boolean,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (RuleInput) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("최근 샘플") },
        text = {
            if (!permissionGranted) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isSms) "문자 읽기 권한이 꺼져 있습니다." else "알림 접근 권한이 꺼져 있습니다.")
                Text(
                    if (isSms) "허용하면 최근 문자 50건을 바로 불러옵니다."
                    else "켠 뒤에 도착하는 알림부터 쌓입니다. 이미 지나간 알림은 읽을 수 없습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onOpenSettings) { Text("설정 열기") }
            }
            else if (samples.isEmpty()) Text(
                if (isSms) "최근 문자가 없습니다."
                else "아직 수집된 알림이 없습니다. 알림이 하나 도착하면 여기에 나타납니다."
            )
            else Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                samples.forEachIndexed { index, item ->
                    TextButton(onClick = { onSelect(item) }, Modifier.fillMaxWidth()) {
                        Text("${index + 1}. ${item.summaryForWizard()}", Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun WizardCheckRow(label: String, checked: Boolean, onCheckedChange: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { onCheckedChange() })
        Text(label)
    }
}

@Composable
private fun RadioText(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(label)
    }
}

private fun wizardTitle(step: Int): String = when (step) {
    0 -> "이름"
    1 -> "어디서"
    2 -> "샘플"
    3 -> "포맷 지정"
    4 -> "조건·옵션"
    5 -> "보낼 항목"
    else -> "미리보기"
}

private fun fieldDescription(field: RuleField): String = when (field.type) {
    RuleFieldType.NUMBER -> "숫자"
    RuleFieldType.DATETIME -> "날짜·시간 ${field.format.orEmpty()}"
    RuleFieldType.IGNORE -> "전송하지 않음"
    else -> "텍스트"
}

private fun placeholderFor(field: RuleField): String = when (field.type) {
    RuleFieldType.NUMBER -> "{${field.key}:number}"
    RuleFieldType.DATETIME -> "{${field.key}:datetime(${field.format ?: "MM/dd HH:mm"})}"
    RuleFieldType.IGNORE -> "{_}"
    else -> "{${field.key}}"
}

private fun suggestedKey(value: String): String {
    val key = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
    return key.ifEmpty { "field" }
}

private fun RuleInput.summaryForWizard(): String = when (channel) {
    RuleSourceKind.NOTIFICATION -> "${appName.ifBlank { packageName }} · ${title.ifBlank { text }.replace('\n', ' ').take(70)}"
    else -> "${sender.ifBlank { "발신번호 없음" }} · ${text.replace('\n', ' ').take(70)}"
}

private fun RuleInput.textForWizard(): String = if (channel == RuleSourceKind.NOTIFICATION) {
    listOf(title, text).filter(String::isNotBlank).joinToString("\n")
} else text

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
