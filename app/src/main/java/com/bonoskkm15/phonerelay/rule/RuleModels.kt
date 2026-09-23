package com.bonoskkm15.phonerelay.rule

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** 규칙이 받을 수 있는 입력 출처. 통화·위치는 다음 단계에서 추가한다. */
object RuleSourceKind {
    const val SMS = "sms"
    const val NOTIFICATION = "notification"
}

object RuleEvent {
    const val RECEIVED = "received"
    const val POSTED = "posted"
    const val REMOVED = "removed"
}

object RuleFieldType {
    const val TEXT = "text"
    const val NUMBER = "number"
    const val DATETIME = "datetime"
    const val IGNORE = "ignore"

    val ALL = setOf(TEXT, NUMBER, DATETIME, IGNORE)
}

data class RuleField(
    val key: String,
    val label: String,
    val type: String = RuleFieldType.TEXT,
    val format: String? = null,
    val mapping: Map<String, String> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("label", label)
        put("type", type)
        if (!format.isNullOrBlank()) put("format", format)
        put("map", JSONObject().apply { mapping.forEach { (k, v) -> put(k, v) } })
    }

    companion object {
        fun fromJson(json: JSONObject): RuleField? {
            val key = json.optString("key").trim()
            if (key.isEmpty() || !key.matches(KEY_PATTERN)) return null
            val type = json.optString("type", RuleFieldType.TEXT)
                .takeIf { it in RuleFieldType.ALL } ?: RuleFieldType.TEXT
            val mapping = buildMap {
                val map = json.optJSONObject("map") ?: return@buildMap
                map.keys().forEach { put(it, map.optString(it)) }
            }
            return RuleField(
                key = key,
                label = json.optString("label", key).trim().ifEmpty { key },
                type = type,
                format = json.optString("format").trim().ifEmpty { null },
                mapping = mapping,
            )
        }

        private val KEY_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

data class RuleSource(
    val kind: String,
    val senders: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
    val events: List<String> = listOf(RuleEvent.RECEIVED),
    /** 알림 원문을 제목·내용·제목+내용 중 어떤 형태로 템플릿에 넣을지. */
    val notificationText: String = NOTIFICATION_TITLE_AND_TEXT,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("senders", JSONArray(senders))
        put("packages", JSONArray(packages))
        put("events", JSONArray(events))
        put("text", notificationText)
    }

    companion object {
        const val NOTIFICATION_TITLE = "title"
        const val NOTIFICATION_TEXT = "text"
        const val NOTIFICATION_TITLE_AND_TEXT = "title_and_text"

        fun fromJson(json: JSONObject): RuleSource = RuleSource(
            kind = json.optString("kind", RuleSourceKind.SMS),
            senders = json.stringList("senders").map { it.filter(Char::isDigit) }.filter(String::isNotEmpty),
            packages = json.stringList("packages").filter(String::isNotEmpty),
            events = json.stringList("events").ifEmpty { listOf(RuleEvent.RECEIVED) },
            notificationText = json.optString("text", NOTIFICATION_TITLE_AND_TEXT)
                .takeIf { it in setOf(NOTIFICATION_TITLE, NOTIFICATION_TEXT, NOTIFICATION_TITLE_AND_TEXT) }
                ?: NOTIFICATION_TITLE_AND_TEXT,
        )
    }
}

/**
 * 한 출처(앱·발신번호) 안에서 보낼 메시지 형식 하나.
 * 원문 전송 규칙에서 쓰며, 등록된 형식 중 하나라도 맞아야 전송한다.
 */
data class RuleFormat(
    val name: String,
    val kind: String = KIND_STARTS_WITH,
    val value: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("kind", kind)
        .put("value", value)

    /** [normalized]는 TemplateCompiler.normalize 를 거친 텍스트. */
    fun matches(normalized: String): Boolean {
        if (value.isEmpty()) return false
        return when (kind) {
            KIND_CONTAINS -> normalized.contains(value)
            KIND_REGEX -> runCatching { Regex(value).containsMatchIn(normalized) }.getOrDefault(false)
            else -> normalized.lineSequence().firstOrNull().orEmpty().startsWith(value)
        }
    }

    fun describe(): String = when (kind) {
        KIND_CONTAINS -> "'$value' 포함"
        KIND_REGEX -> "정규식 $value"
        else -> "첫 줄이 '$value'(으)로 시작"
    }

    companion object {
        const val KIND_STARTS_WITH = "starts_with"
        const val KIND_CONTAINS = "contains"
        const val KIND_REGEX = "regex"
        val KINDS = setOf(KIND_STARTS_WITH, KIND_CONTAINS, KIND_REGEX)

        fun fromJson(json: JSONObject): RuleFormat? {
            val value = json.optString("value").trim().ifEmpty { return null }
            val kind = json.optString("kind", KIND_STARTS_WITH).takeIf { it in KINDS } ?: KIND_STARTS_WITH
            return RuleFormat(
                name = json.optString("name").trim().ifEmpty { value },
                kind = kind,
                value = value,
            )
        }

        /** 등록 순서대로 처음 맞는 형식. 목록이 비어 있으면 null. */
        fun firstMatch(formats: List<RuleFormat>, normalized: String): RuleFormat? =
            formats.firstOrNull { it.matches(normalized) }

        /**
         * 샘플에서 형식 조건을 자동으로 제안한다.
         * 첫 줄에서 숫자·금액이 시작되기 전까지를 시작 문구로 쓴다. 예: "출금 10,000원" → "출금".
         */
        fun suggestFrom(text: String): RuleFormat? {
            val firstLine = TemplateCompiler.normalize(text).lineSequence().firstOrNull()?.trim().orEmpty()
            if (firstLine.isEmpty()) return null
            val cut = firstLine.indexOfFirst { it.isDigit() }
            val prefix = (if (cut > 0) firstLine.substring(0, cut) else firstLine)
                .trimEnd(' ', ':', '-', '·', '/', '(', '[')
                .trim()
                .ifEmpty { firstLine.take(10) }
                .take(20)
            return RuleFormat(name = prefix, kind = KIND_STARTS_WITH, value = prefix)
        }
    }
}

data class RuleMatch(
    val mode: String = MODE_FULL,
    val include: List<String> = emptyList(),
    val exclude: List<String> = listOf("인증번호"),
    val rawOnly: Boolean = false,
    val reportMismatch: Boolean = true,
    val maskPatterns: List<String> = listOf("(?m)^고객명 .*$"),
    /** 비어 있으면 모든 메시지, 있으면 이 중 하나라도 맞는 메시지만 처리한다. */
    val formats: List<RuleFormat> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("include", JSONArray(include))
        put("exclude", JSONArray(exclude))
        put("rawOnly", rawOnly)
        put("reportMismatch", reportMismatch)
        put("maskPatterns", JSONArray(maskPatterns))
        put("formats", JSONArray().also { array -> formats.forEach { array.put(it.toJson()) } })
    }

    companion object {
        const val MODE_FULL = "full"
        const val MODE_FIND = "find"

        fun fromJson(json: JSONObject): RuleMatch = RuleMatch(
            mode = json.optString("mode", MODE_FULL).takeIf { it == MODE_FIND } ?: MODE_FULL,
            include = json.stringList("include"),
            exclude = json.stringList("exclude"),
            rawOnly = json.optBoolean("rawOnly", false),
            reportMismatch = json.optBoolean("reportMismatch", true),
            maskPatterns = json.stringList("maskPatterns"),
            formats = buildList {
                val array = json.optJSONArray("formats") ?: JSONArray()
                for (i in 0 until array.length()) {
                    RuleFormat.fromJson(array.optJSONObject(i) ?: continue)?.let(::add)
                }
            },
        )
    }
}

data class RuleMeta(
    val receivedAt: Boolean = true,
    val channel: Boolean = true,
    val sender: Boolean = false,
    val senderMask: Boolean = true,
    val app: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("receivedAt", receivedAt)
        .put("channel", channel)
        .put("sender", sender)
        .put("senderMask", senderMask)
        .put("app", app)

    companion object {
        fun fromJson(json: JSONObject): RuleMeta = RuleMeta(
            receivedAt = json.optBoolean("receivedAt", true),
            channel = json.optBoolean("channel", true),
            sender = json.optBoolean("sender", false),
            senderMask = json.optBoolean("senderMask", true),
            app = json.optBoolean("app", false),
        )
    }
}

data class RuleOutput(
    val fields: List<String> = emptyList(),
    val occurredAtField: String? = null,
    val meta: RuleMeta = RuleMeta(),
    val includeRaw: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("fields", JSONArray(fields))
        if (!occurredAtField.isNullOrBlank()) put("occurredAtField", occurredAtField)
        put("meta", meta.toJson())
        put("includeRaw", includeRaw)
    }

    companion object {
        fun fromJson(json: JSONObject): RuleOutput = RuleOutput(
            fields = json.stringList("fields"),
            occurredAtField = json.optString("occurredAtField").trim().ifEmpty { null },
            meta = RuleMeta.fromJson(json.optJSONObject("meta") ?: JSONObject()),
            includeRaw = json.optBoolean("includeRaw", false),
        )
    }
}

/** 사용자가 만든 수집 규칙(레시피). */
data class PhoneRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String,
    val enabled: Boolean = true,
    val source: RuleSource,
    val sample: String = "",
    val template: String = "",
    val fields: List<RuleField> = emptyList(),
    val match: RuleMatch = RuleMatch(),
    val output: RuleOutput = RuleOutput(),
    val createdAt: String,
    val updatedAt: String,
    val lastMatchedAt: String? = null,
    val todayMatchCount: Int = 0,
    val matchCountDate: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type)
        put("enabled", enabled)
        put("source", source.toJson())
        put("sample", sample)
        put("template", template)
        put("fields", JSONArray().also { array -> fields.forEach { array.put(it.toJson()) } })
        put("match", match.toJson())
        put("output", output.toJson())
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        if (!lastMatchedAt.isNullOrBlank()) put("lastMatchedAt", lastMatchedAt)
        put("todayMatchCount", todayMatchCount)
        if (!matchCountDate.isNullOrBlank()) put("matchCountDate", matchCountDate)
    }

    companion object {
        fun fromJson(json: JSONObject): PhoneRule? {
            val id = json.optString("id").trim().ifEmpty { return null }
            val name = json.optString("name").trim().ifEmpty { return null }
            val type = json.optString("type").trim().ifEmpty { slug(name) }
            val fields = buildList {
                val array = json.optJSONArray("fields") ?: JSONArray()
                for (i in 0 until array.length()) {
                    RuleField.fromJson(array.optJSONObject(i) ?: continue)?.let(::add)
                }
            }
            val now = java.time.OffsetDateTime.now().toString()
            return PhoneRule(
                id = id,
                name = name,
                type = type,
                enabled = json.optBoolean("enabled", true),
                source = RuleSource.fromJson(json.optJSONObject("source") ?: JSONObject()),
                sample = json.optString("sample"),
                template = json.optString("template"),
                fields = fields,
                match = RuleMatch.fromJson(json.optJSONObject("match") ?: JSONObject()),
                output = RuleOutput.fromJson(json.optJSONObject("output") ?: JSONObject()),
                createdAt = json.optString("createdAt", now),
                updatedAt = json.optString("updatedAt", now),
                lastMatchedAt = json.optString("lastMatchedAt").trim().ifEmpty { null },
                todayMatchCount = json.optInt("todayMatchCount", 0),
                matchCountDate = json.optString("matchCountDate").trim().ifEmpty { null },
            )
        }

        fun slug(name: String): String = name
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "rule" }

        /** 설치 직후 표시할 예시 규칙. 기본값은 꺼져 있다. */
        fun defaultKbCardRule(now: String): PhoneRule = PhoneRule(
            name = "KB 신용카드 승인",
            type = "kb_card_approval",
            enabled = false,
            source = RuleSource(kind = RuleSourceKind.SMS),
            sample = DEFAULT_KB_SAMPLE,
            template = DEFAULT_KB_TEMPLATE,
            fields = listOf(
                RuleField("card_last4", "카드끝자리", RuleFieldType.TEXT),
                RuleField(
                    "status", "상태", RuleFieldType.TEXT,
                    mapping = mapOf("승인" to "approved", "승인취소" to "cancelled", "취소" to "cancelled", "거절" to "declined"),
                ),
                RuleField("amount", "금액", RuleFieldType.NUMBER),
                RuleField("payment_type", "결제방식", RuleFieldType.TEXT),
                RuleField("merchant", "가맹점", RuleFieldType.TEXT),
                RuleField("approved_at", "승인시각", RuleFieldType.DATETIME, "MM/dd HH:mm"),
                RuleField("cumulative", "누적금액", RuleFieldType.NUMBER),
            ),
            match = RuleMatch(
                exclude = listOf("인증번호"),
                maskPatterns = listOf("(?m)^고객명 .*$"),
            ),
            output = RuleOutput(
                fields = listOf("card_last4", "status", "amount", "payment_type", "merchant", "approved_at", "cumulative"),
                occurredAtField = "approved_at",
                meta = RuleMeta(receivedAt = true, channel = true, sender = false, app = false),
                includeRaw = false,
            ),
            createdAt = now,
            updatedAt = now,
        )

        const val DEFAULT_KB_SAMPLE = """KB국민카드6005
승인
83,600 원(일시불)
제주화로집 신논현점
고객명 김*만님
승인시각 09/14 21:38
누적 5,520,828원"""

        const val DEFAULT_KB_TEMPLATE = """KB국민카드{card_last4}
{status}
{amount:number} 원({payment_type})
{merchant}
고객명 {_}
승인시각 {approved_at:datetime(MM/dd HH:mm)}
누적 {cumulative:number}원"""
    }
}

private fun JSONObject.stringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val value = array.optString(i).trim()
            if (value.isNotEmpty()) add(value)
        }
    }
}
