package com.bonoskkm15.phonerelay.rule

import android.content.Context
import com.bonoskkm15.phonerelay.core.DeviceInfo
import com.bonoskkm15.phonerelay.core.EventQueue
import com.bonoskkm15.phonerelay.core.PhoneEvent
import com.bonoskkm15.phonerelay.core.SendScheduler
import com.bonoskkm15.phonerelay.core.Time
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class RulePreview(
    val considered: Boolean,
    val matched: Boolean,
    val fields: Map<String, Any?> = emptyMap(),
    val occurredAt: String,
    val normalizedText: String = "",
    val failedLine: Int? = null,
    val error: String? = null,
)

data class RuleProcessReport(
    val matched: Int = 0,
    val mismatch: Int = 0,
    val ignored: Int = 0,
)

/** 활성 규칙을 순서대로 평가해 공통 PhoneEvent와 전송 큐를 만든다. */
object RuleEngine {
    private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun process(ctx: Context, input: RuleInput, trigger: String = PhoneEvent.TRIGGER_EVENT): RuleProcessReport {
        val queue = EventQueue.get(ctx)
        var matched = 0
        var mismatch = 0
        var ignored = 0
        var insertedAny = false

        RuleStore.list(ctx).filter { it.enabled }.forEach { rule ->
            val preview = preview(rule, input)
            if (!preview.considered) {
                ignored++
                return@forEach
            }
            if (!preview.matched) {
                if (rule.match.reportMismatch) {
                    val event = PhoneEvent(
                        source = "rule",
                        type = "match_error",
                        trigger = trigger,
                        occurredAt = receivedIso(input.receivedAtMillis),
                        data = mismatchData(rule, input, preview),
                    )
                    if (queue.enqueue(ctx, event, "${input.dedupKey}:${rule.id}:match_error")) {
                        insertedAny = true
                        mismatch++
                    }
                } else {
                    ignored++
                }
                return@forEach
            }

            val event = PhoneEvent(
                source = "rule",
                type = rule.type,
                trigger = trigger,
                occurredAt = preview.occurredAt,
                data = outputData(rule, input, preview),
            )
            if (queue.enqueue(ctx, event, "${input.dedupKey}:${rule.id}")) {
                RuleStore.recordMatch(ctx, rule.id, receivedIso(input.receivedAtMillis))
                insertedAny = true
                matched++
            }
        }

        if (insertedAny) SendScheduler.sendNow(ctx)
        return RuleProcessReport(matched, mismatch, ignored)
    }

    /** 마법사 미리보기와 테스트 코드가 공유하는 순수 평가 함수. */
    fun preview(rule: PhoneRule, input: RuleInput): RulePreview {
        if (!sourceMatches(rule, input)) return RulePreview(false, false, occurredAt = receivedIso(input.receivedAtMillis))
        val raw = sourceText(rule, input)
        if (rule.match.include.any { it.isNotEmpty() && !raw.contains(it) }) {
            return RulePreview(false, false, occurredAt = receivedIso(input.receivedAtMillis))
        }
        if (rule.match.exclude.any { it.isNotEmpty() && raw.contains(it) }) {
            return RulePreview(false, false, occurredAt = receivedIso(input.receivedAtMillis))
        }

        if (rule.match.rawOnly) {
            val text = mask(raw, rule.match.maskPatterns)
            return RulePreview(
                considered = true,
                matched = true,
                fields = mapOf("text" to text),
                occurredAt = receivedIso(input.receivedAtMillis),
                normalizedText = TemplateCompiler.normalize(raw),
            )
        }

        val compiled = try {
            TemplateCompiler.compile(rule.template, rule.fields)
        } catch (e: TemplateCompileException) {
            return RulePreview(
                considered = true,
                matched = false,
                occurredAt = receivedIso(input.receivedAtMillis),
                error = e.message ?: "템플릿 컴파일 오류",
            )
        }
        return when (val result = compiled.match(raw, rule.match.mode, input.receivedAtMillis)) {
            is TemplateMatch.Success -> {
                val output = result.values.filterKeys { it in rule.output.fields }
                val occurred = (rule.output.occurredAtField?.let { result.values[it] } as? String)
                    ?: receivedIso(input.receivedAtMillis)
                RulePreview(true, true, output, occurred, result.normalizedText)
            }
            is TemplateMatch.Failure -> RulePreview(
                considered = true,
                matched = false,
                occurredAt = receivedIso(input.receivedAtMillis),
                failedLine = result.failedLine,
                error = result.reason,
            )
        }
    }

    fun previewJson(ctx: Context, rule: PhoneRule, input: RuleInput): String {
        val preview = preview(rule, input)
        if (!preview.matched) return JSONObject()
            .put("preview_error", preview.error ?: "매칭 실패")
            .put("failed_line", preview.failedLine ?: JSONObject.NULL)
            .toString(2)
        val event = PhoneEvent(
            source = "rule",
            type = rule.type,
            trigger = PhoneEvent.TRIGGER_MANUAL,
            occurredAt = preview.occurredAt,
            data = outputData(rule, input, preview),
        )
        return event.toJson(DeviceInfo.of(ctx)).toString(2)
    }

    /** 마법사에서 검증한 샘플만 수동 트리거로 큐에 넣는다. */
    fun sendPreview(ctx: Context, rule: PhoneRule, input: RuleInput): Boolean {
        val preview = preview(rule, input)
        if (!preview.considered || !preview.matched) return false
        val event = PhoneEvent(
            source = "rule",
            type = rule.type,
            trigger = PhoneEvent.TRIGGER_MANUAL,
            occurredAt = preview.occurredAt,
            data = outputData(rule, input, preview),
        )
        val inserted = EventQueue.get(ctx).enqueue(ctx, event, "preview:${rule.id}:${input.dedupKey}")
        if (inserted) SendScheduler.sendNow(ctx)
        return inserted
    }

    private fun sourceMatches(rule: PhoneRule, input: RuleInput): Boolean {
        val source = rule.source
        if (source.kind == RuleSourceKind.SMS) {
            if (input.channel != "sms" && input.channel != "mms") return false
            if (source.senders.isNotEmpty() && digits(input.sender) !in source.senders) return false
            return true
        }
        if (source.kind == RuleSourceKind.NOTIFICATION) {
            if (input.channel != RuleSourceKind.NOTIFICATION) return false
            if (source.packages.isNotEmpty() && input.packageName !in source.packages) return false
            return input.action in source.events
        }
        return false
    }

    private fun sourceText(rule: PhoneRule, input: RuleInput): String =
        if (rule.source.kind != RuleSourceKind.NOTIFICATION) input.text
        else when (rule.source.notificationText) {
            RuleSource.NOTIFICATION_TITLE -> input.title
            RuleSource.NOTIFICATION_TEXT -> input.text
            else -> listOf(input.title, input.text).filter(String::isNotBlank).joinToString("\n")
        }

    private fun outputData(rule: PhoneRule, input: RuleInput, preview: RulePreview): JSONObject = JSONObject().apply {
        put("rule_id", rule.id)
        put("rule_name", rule.name)
        if (rule.output.meta.channel) put("channel", input.channel)
        if (rule.output.meta.receivedAt) put("received_at", receivedIso(input.receivedAtMillis))
        if (rule.output.meta.sender) put("sender", if (rule.output.meta.senderMask) maskSender(input.sender) else input.sender)
        if (rule.output.meta.app && input.channel == RuleSourceKind.NOTIFICATION) {
            put("app_name", input.appName.ifBlank { input.packageName })
            put("package_name", input.packageName)
        }
        val fields = JSONObject()
        preview.fields.forEach { (key, value) -> if (value != null) fields.put(key, value) }
        put("fields", fields)
        if (rule.output.includeRaw) put("raw", mask(sourceText(rule, input), rule.match.maskPatterns))
    }

    private fun mismatchData(rule: PhoneRule, input: RuleInput, preview: RulePreview): JSONObject = JSONObject()
        .put("rule_id", rule.id)
        .put("rule_name", rule.name)
        .put("rule_type", rule.type)
        .put("channel", input.channel)
        .put("raw", mask(sourceText(rule, input), rule.match.maskPatterns))
        .put("failed_line", preview.failedLine ?: JSONObject.NULL)
        .put("reason", preview.error ?: "template_mismatch")

    private fun mask(raw: String, patterns: List<String>): String {
        var result = raw
        patterns.forEach { pattern ->
            if (pattern.isBlank()) return@forEach
            result = runCatching { result.replace(Regex(pattern), "") }.getOrDefault(result)
        }
        return result.trim()
    }

    private fun maskSender(sender: String): String {
        val value = sender.trim()
        val digits = digits(value)
        return if (digits.length >= 7) {
            "${digits.take(3)}-****-${digits.takeLast(4)}"
        } else if (value.length > 4) {
            "****${value.takeLast(4)}"
        } else {
            "****"
        }
    }

    private fun digits(value: String): String = value.filter(Char::isDigit)

    private fun receivedIso(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(SEOUL).toOffsetDateTime().format(DATE)
}
