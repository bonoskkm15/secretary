package com.bonoskkm15.phonerelay.rule

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 한 앱에서 여러 형식이 섞여 올 때 등록한 형식만 보내는지 검증한다. */
class RuleFormatTest {

    private val withdrawal = "출금 10,000원\n김*만님 09/23 09:07 626402-**-***742\n카카오페이 FBS출금 10,000"
    private val deposit = "입금 50,000원\n김*만님 09/23 10:00 626402-**-***742\n홍길동 50,000"
    private val fx = "외환 안내\n김*만님 지정통화 환율 안내입니다.\n2026.09.23 09:00 현재 USD(미국 달러) 매매기준율 1,352.00원"

    private val baseRule = PhoneRule(
        name = "KB스타뱅킹",
        type = "kbstar_push",
        source = RuleSource(
            kind = RuleSourceKind.NOTIFICATION,
            packages = listOf("com.kbstar.kbbank"),
            events = listOf(RuleEvent.POSTED),
        ),
        match = RuleMatch(
            rawOnly = true,
            maskPatterns = listOf("\\S+님"),
            formats = listOf(
                RuleFormat("출금", RuleFormat.KIND_STARTS_WITH, "출금"),
                RuleFormat("입금", RuleFormat.KIND_STARTS_WITH, "입금"),
            ),
        ),
        createdAt = "2026-09-23T00:00:00+09:00",
        updatedAt = "2026-09-23T00:00:00+09:00",
    )

    private fun input(title: String, text: String) = RuleInput(
        channel = RuleSourceKind.NOTIFICATION,
        packageName = "com.kbstar.kbbank",
        appName = "KB스타뱅킹",
        title = title,
        text = text,
        receivedAtMillis = 1_790_000_000_000,
        dedupKey = "test:$title",
        action = RuleEvent.POSTED,
    )

    private fun split(message: String) = message.substringBefore('\n') to message.substringAfter('\n')

    @Test
    fun `등록한 포맷의 메시지는 원문과 포맷 이름으로 전송된다`() {
        val (title, text) = split(withdrawal)
        val preview = RuleEngine.preview(baseRule, input(title, text))

        assertTrue(preview.matched)
        assertEquals("출금", preview.format)
        val raw = preview.fields["text"] as String
        assertTrue(raw.startsWith("출금 10,000원"))
        assertFalse("이름 마스킹이 안 됐습니다: $raw", raw.contains("김*만"))
    }

    @Test
    fun `두 번째 포맷도 매칭된다`() {
        val (title, text) = split(deposit)
        val preview = RuleEngine.preview(baseRule, input(title, text))
        assertTrue(preview.matched)
        assertEquals("입금", preview.format)
    }

    @Test
    fun `등록하지 않은 포맷은 조용히 버린다`() {
        val (title, text) = split(fx)
        val preview = RuleEngine.preview(baseRule, input(title, text))
        assertFalse(preview.considered)
        assertFalse(preview.matched)
    }

    @Test
    fun `포맷 목록이 비어 있으면 모든 메시지를 보내고 format은 없다`() {
        val rule = baseRule.copy(match = baseRule.match.copy(formats = emptyList()))
        val (title, text) = split(fx)
        val preview = RuleEngine.preview(rule, input(title, text))
        assertTrue(preview.matched)
        assertNull(preview.format)
    }

    @Test
    fun `포함과 정규식 조건`() {
        val normalized = TemplateCompiler.normalize(fx)
        assertTrue(RuleFormat("환율", RuleFormat.KIND_CONTAINS, "매매기준율").matches(normalized))
        assertTrue(RuleFormat("환율", RuleFormat.KIND_REGEX, "USD\\(.+\\) 매매기준율").matches(normalized))
        assertFalse(RuleFormat("잘못된", RuleFormat.KIND_REGEX, "(").matches(normalized))
        assertFalse(RuleFormat("출금", RuleFormat.KIND_STARTS_WITH, "출금").matches(normalized))
    }

    @Test
    fun `샘플에서 시작 문구를 제안한다`() {
        assertEquals("출금", RuleFormat.suggestFrom(withdrawal)?.value)
        assertEquals("외환 안내", RuleFormat.suggestFrom(fx)?.value)
        assertEquals("출금", RuleFormat.suggestFrom("[Web발신]\n출금 3,000원")?.value)
        assertNull(RuleFormat.suggestFrom("   "))
    }

    @Test
    fun `포맷 목록이 JSON 왕복 후에도 유지된다`() {
        val json = JSONObject(baseRule.toJson().toString())
        val restored = PhoneRule.fromJson(json)!!
        assertEquals(baseRule.match.formats, restored.match.formats)
    }
}
