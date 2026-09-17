package com.bonoskkm15.phonerelay.rule

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import java.time.temporal.TemporalAccessor
import java.time.temporal.ChronoUnit
import java.util.Locale

class TemplateCompileException(message: String) : IllegalArgumentException(message)

sealed interface TemplateMatch {
    data class Success(val values: Map<String, Any?>, val normalizedText: String) : TemplateMatch
    data class Failure(val reason: String, val failedLine: Int) : TemplateMatch
}

/** Android 의존성이 없는 템플릿 컴파일러. JVM 단위 테스트에서 직접 검증할 수 있다. */
object TemplateCompiler {
    private val PLACEHOLDER = Regex(
        """\{([A-Za-z_][A-Za-z0-9_]*)(?::(number|text|datetime)(?:\(([^)]*)\))?)?\}"""
    )
    private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun normalize(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .dropWhile { it.startsWith("[") && it.endsWith("]") }
        .joinToString("\n")

    fun compile(template: String, fields: List<RuleField>): CompiledTemplate {
        val normalized = normalize(template)
        if (normalized.isEmpty()) throw TemplateCompileException("템플릿이 비어 있습니다")
        val fieldMap = fields.associateBy { it.key }
        if (fieldMap.size != fields.size) throw TemplateCompileException("필드 키가 중복됩니다")
        fields.forEach {
            if (!it.key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                throw TemplateCompileException("잘못된 필드 키: ${it.key}")
            }
            if (it.type !in RuleFieldType.ALL) throw TemplateCompileException("잘못된 필드 타입: ${it.type}")
            if (it.type == RuleFieldType.DATETIME && it.format.isNullOrBlank()) {
                throw TemplateCompileException("날짜·시간 필드 ${it.key}의 형식이 없습니다")
            }
        }

        val captures = mutableListOf<Capture>()
        val regex = StringBuilder()
        var cursor = 0
        for (match in PLACEHOLDER.findAll(normalized)) {
            val literal = normalized.substring(cursor, match.range.first)
            if (literal.isEmpty() && captures.isNotEmpty()) {
                throw TemplateCompileException("인접한 자리표시자는 구분 문자가 필요합니다")
            }
            appendFlexibleLiteral(regex, literal)

            val key = match.groupValues[1]
            if (key != "_" && captures.any { it.key == key }) {
                throw TemplateCompileException("템플릿에서 필드 키가 중복됩니다: ${key}")
            }
            val field = if (key == "_") null else fieldMap[key]
            if (key != "_" && field == null) throw TemplateCompileException("정의되지 않은 필드: $key")
            val overrideType = match.groupValues[2].takeIf { it.isNotBlank() }
            val type = overrideType ?: field?.type ?: RuleFieldType.IGNORE
            val format = match.groupValues[3].takeIf { it.isNotBlank() } ?: field?.format
            if (type == RuleFieldType.DATETIME && format.isNullOrBlank()) {
                throw TemplateCompileException("날짜·시간 필드 ${key}의 형식이 없습니다")
            }
            if (type !in RuleFieldType.ALL) throw TemplateCompileException("지원하지 않는 필드 타입: $type")

            val atLineEnd = normalized.getOrNull(match.range.last + 1) == null ||
                normalized.getOrNull(match.range.last + 1) == '\n'
            val expression = when {
                key == "_" || type == RuleFieldType.IGNORE -> ".*?"
                type == RuleFieldType.NUMBER -> "-?[\\d,]+(?:\\.\\d+)?"
                type == RuleFieldType.DATETIME -> dateTimeRegex(format!!)
                atLineEnd -> ".+"
                else -> ".+?"
            }
            regex.append('(').append(expression).append(')')
            captures += Capture(key, type, format, field?.mapping.orEmpty(), captures.size + 1)
            cursor = match.range.last + 1
        }

        val tail = normalized.substring(cursor)
        if (tail.contains('{') || tail.contains('}')) {
            throw TemplateCompileException("인식할 수 없는 자리표시자가 있습니다")
        }
        appendFlexibleLiteral(regex, tail)
        if (captures.isEmpty()) throw TemplateCompileException("자리표시자를 하나 이상 지정하세요")
        return CompiledTemplate(normalized, regex.toString(), captures)
    }

    data class CompiledTemplate(
        val normalizedTemplate: String,
        val regexSource: String,
        val captures: List<Capture>,
    ) {
        private val regex = Regex(regexSource)

        fun match(text: String, mode: String, receivedAtMillis: Long): TemplateMatch {
            val normalized = normalize(text)
            val result: MatchResult? = if (mode == RuleMatch.MODE_FIND) {
                regex.find(normalized)
            } else {
                regex.matchEntire(normalized)
            }
            if (result == null) return TemplateMatch.Failure("template_mismatch", failedLine(normalized))
            val rawValues = captures.associate { it.key to result.groupValues[it.groupIndex] }
            val converted = buildMap {
                captures.forEach { capture ->
                    if (capture.key == "_") return@forEach
                    val raw = rawValues[capture.key] ?: return@forEach
                    put(capture.key, convert(capture, raw, receivedAtMillis))
                }
            }
            return TemplateMatch.Success(converted, normalized)
        }

        private fun failedLine(input: String): Int {
            val inputLines = input.lines()
            val templateLines = normalizedTemplate.lines()
            for (i in inputLines.indices) {
                val expectedLiteral = templateLines.getOrNull(i)
                    ?.replace(PLACEHOLDER, "")
                    ?.trim()
                    .orEmpty()
                if (expectedLiteral.isNotEmpty() && !inputLines[i].contains(expectedLiteral)) return i + 1
            }
            return minOf(inputLines.size, templateLines.size).coerceAtLeast(1)
        }

        private fun convert(capture: Capture, raw: String, receivedAtMillis: Long): Any? {
            val value = raw.trim()
            return when (capture.type) {
                RuleFieldType.TEXT -> capture.mapping[value] ?: value
                RuleFieldType.IGNORE -> null
                RuleFieldType.NUMBER -> {
                    val number = value.replace(",", "")
                    if (number.contains('.')) number.toDouble() else number.toLong()
                }
                RuleFieldType.DATETIME -> parseDateTime(value, capture.format!!, receivedAtMillis)
                else -> value
            }
        }
    }

    data class Capture(
        val key: String,
        val type: String,
        val format: String?,
        val mapping: Map<String, String>,
        val groupIndex: Int,
    )

    private fun appendFlexibleLiteral(out: StringBuilder, literal: String) {
        var i = 0
        while (i < literal.length) {
            when {
                literal[i] == '\n' -> {
                    out.append("\\s*\\n\\s*")
                    i++
                }
                literal[i].isWhitespace() -> {
                    while (i < literal.length && literal[i].isWhitespace() && literal[i] != '\n') i++
                    out.append("\\s+")
                }
                else -> {
                    out.append(Regex.escape(literal[i].toString()))
                    i++
                }
            }
        }
    }

    private fun dateTimeRegex(format: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < format.length) {
            val c = format[i]
            if (c == '\'') {
                i++
                val start = i
                while (i < format.length && format[i] != '\'') i++
                appendFlexibleLiteral(out, format.substring(start, i))
                if (i < format.length) i++
                continue
            }
            if (c in "yYuMdDHhmsS") {
                var j = i + 1
                while (j < format.length && format[j] == c) j++
                val count = j - i
                val expression = when (c) {
                    'y', 'Y', 'u' -> "\\d{$count}"
                    'M', 'd', 'D', 'H', 'h' -> if (count == 1) "\\d{1,2}" else "\\d{$count}"
                    else -> "\\d{$count}"
                }
                out.append(expression)
                i = j
            } else {
                appendFlexibleLiteral(out, c.toString())
                i++
            }
        }
        return out.toString()
    }

    private fun parseDateTime(raw: String, format: String, receivedAtMillis: Long): String {
        try {
            val formatter = DateTimeFormatter.ofPattern(format, Locale.KOREA)
                .withResolverStyle(ResolverStyle.SMART)
            val parsed: TemporalAccessor = formatter.parse(raw)
            val received = Instant.ofEpochMilli(receivedAtMillis).atZone(SEOUL)
            val hasYear = parsed.isSupported(ChronoField.YEAR) || parsed.isSupported(ChronoField.YEAR_OF_ERA)
            val year = when {
                parsed.isSupported(ChronoField.YEAR) -> parsed.get(ChronoField.YEAR)
                parsed.isSupported(ChronoField.YEAR_OF_ERA) -> parsed.get(ChronoField.YEAR_OF_ERA)
                else -> received.year
            }
            val month = parsed.getOrDefault(ChronoField.MONTH_OF_YEAR, 1)
            val day = parsed.getOrDefault(ChronoField.DAY_OF_MONTH, 1)
            val hour = parsed.getOrDefault(ChronoField.HOUR_OF_DAY, 0)
            val minute = parsed.getOrDefault(ChronoField.MINUTE_OF_HOUR, 0)
            val second = parsed.getOrDefault(ChronoField.SECOND_OF_MINUTE, 0)
            var time = ZonedDateTime.of(year, month, day, hour, minute, second, 0, SEOUL)
            if (!hasYear && time.isAfter(received.plusDays(1))) time = time.minusYears(1)
            return time.toOffsetDateTime().truncatedTo(ChronoUnit.MILLIS).format(ISO)
        } catch (e: DateTimeException) {
            throw TemplateCompileException("날짜·시간 변환 실패: $raw ($format)")
        } catch (e: NumberFormatException) {
            throw TemplateCompileException("날짜·시간 변환 실패: $raw ($format)")
        }
    }

    private fun TemporalAccessor.getOrDefault(field: ChronoField, default: Int): Int =
        if (isSupported(field)) get(field) else default
}
