package com.bonoskkm15.phonerelay.rule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 구현 지시서 8장의 단위 테스트 10개.
 * Android 의존성 없이 순수 JVM 에서 돈다.
 */
class TemplateCompilerTest {

    private val fields = listOf(
        RuleField("card_last4", "카드끝자리", RuleFieldType.TEXT),
        RuleField(
            "status", "상태", RuleFieldType.TEXT,
            mapping = mapOf(
                "승인" to "approved",
                "승인취소" to "cancelled",
                "취소" to "cancelled",
                "거절" to "declined",
            ),
        ),
        RuleField("amount", "금액", RuleFieldType.NUMBER),
        RuleField("payment_type", "결제방식", RuleFieldType.TEXT),
        RuleField("merchant", "가맹점", RuleFieldType.TEXT),
        RuleField("approved_at", "승인시각", RuleFieldType.DATETIME, "MM/dd HH:mm"),
        RuleField("cumulative", "누적금액", RuleFieldType.NUMBER),
    )

    private val template = PhoneRule.DEFAULT_KB_TEMPLATE
    private val sample = PhoneRule.DEFAULT_KB_SAMPLE

    /** 2026-09-14 21:38:07 +09:00 */
    private val receivedAt = millis(2026, 9, 14, 21, 38, 7)

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        OffsetDateTime.of(y, mo, d, h, mi, s, 0, ZoneOffset.ofHours(9)).toInstant().toEpochMilli()

    private fun match(
        text: String,
        receivedAtMillis: Long = receivedAt,
        mode: String = RuleMatch.MODE_FULL,
    ): TemplateMatch = TemplateCompiler.compile(template, fields).match(text, mode, receivedAtMillis)

    private fun success(text: String, receivedAtMillis: Long = receivedAt): Map<String, Any?> {
        val result = match(text, receivedAtMillis)
        assertTrue("매칭에 실패했습니다: $result", result is TemplateMatch.Success)
        return (result as TemplateMatch.Success).values
    }

    // ── 1. 기본 샘플에서 7개 필드를 정확히 뽑는다 ──────────────────────────────

    @Test
    fun `샘플 문자에서 7개 필드를 추출한다`() {
        val values = success(sample)

        assertEquals(7, values.size)
        assertEquals("6005", values["card_last4"])
        assertEquals("approved", values["status"])
        assertEquals(83600L, values["amount"])
        assertEquals("일시불", values["payment_type"])
        assertEquals("제주화로집 신논현점", values["merchant"])
        assertEquals("2026-09-14T21:38:00+09:00", values["approved_at"])
        assertEquals(5520828L, values["cumulative"])
    }

    // ── 2. [Web발신] 머리글 ────────────────────────────────────────────────

    @Test
    fun `맨 앞 Web발신 줄이 있어도 매칭된다`() {
        val values = success("[Web발신]\n$sample")
        assertEquals("6005", values["card_last4"])
        assertEquals(83600L, values["amount"])
    }

    // ── 3. 연도 경계 ──────────────────────────────────────────────────────

    @Test
    fun `연말 승인 문자를 새해에 받으면 전년도로 본다`() {
        val text = sample.replace("승인시각 09/14 21:38", "승인시각 12/31 23:59")
        val values = success(text, receivedAtMillis = millis(2027, 1, 1, 0, 1))
        assertEquals("2026-12-31T23:59:00+09:00", values["approved_at"])
    }

    // ── 4. 취소·할부 ──────────────────────────────────────────────────────

    @Test
    fun `승인취소와 할부 개월수를 매핑해 변환한다`() {
        val text = sample
            .replace("승인\n", "승인취소\n")
            .replace("(일시불)", "(03개월)")
        val values = success(text)
        assertEquals("cancelled", values["status"])
        assertEquals("03개월", values["payment_type"])
    }

    // ── 5. 매칭 실패 시 실패 줄 ────────────────────────────────────────────

    @Test
    fun `금액 줄이 없으면 실패한 줄 번호를 돌려준다`() {
        val text = sample.lines().filterNot { it.contains("83,600") }.joinToString("\n")
        val result = match(text)
        assertTrue("실패해야 합니다: $result", result is TemplateMatch.Failure)
        assertEquals(3, (result as TemplateMatch.Failure).failedLine)
    }

    // ── 6. 제외 키워드 ────────────────────────────────────────────────────

    @Test
    fun `인증번호가 들어간 문자는 제외 키워드로 걸러진다`() {
        val rule = PhoneRule.defaultKbCardRule("2026-09-14T00:00:00+09:00")
        val input = RuleInput(
            channel = "sms",
            sender = "15881688",
            text = "[Web발신] 인증번호 [123456]를 입력하세요",
            receivedAtMillis = receivedAt,
            dedupKey = "test-otp",
        )

        val preview = RuleEngine.preview(rule.copy(enabled = true), input)

        assertTrue("제외 키워드는 평가 대상에서 빠져야 합니다", !preview.considered)
        assertTrue(!preview.matched)
    }

    // ── 7. 무시 필드 ──────────────────────────────────────────────────────

    @Test
    fun `무시 자리표시자로 지정한 고객명은 결과에 없다`() {
        val values = success(sample)
        assertNull(values["_"])
        assertTrue(values.keys.none { it == "_" })
        assertTrue(values.values.none { it?.toString()?.contains("김") == true })
    }

    // ── 8. 공백 차이 ──────────────────────────────────────────────────────

    @Test
    fun `누적 줄에 공백이 더 있어도 매칭된다`() {
        val text = sample.replace("누적 5,520,828원", "누적  5,520,828 원")
        val values = success(text)
        assertEquals(5520828L, values["cumulative"])
    }

    // ── 9. 잘못된 템플릿 ──────────────────────────────────────────────────

    @Test(expected = TemplateCompileException::class)
    fun `필드 키가 중복되면 컴파일 오류가 난다`() {
        TemplateCompiler.compile(
            "금액 {amount:number} 누적 {amount:number}",
            listOf(RuleField("amount", "금액", RuleFieldType.NUMBER)),
        )
    }

    @Test(expected = TemplateCompileException::class)
    fun `자리표시자가 붙어 있으면 컴파일 오류가 난다`() {
        TemplateCompiler.compile(
            "카드{card_last4}{status}",
            listOf(
                RuleField("card_last4", "카드끝자리", RuleFieldType.TEXT),
                RuleField("status", "상태", RuleFieldType.TEXT),
            ),
        )
    }

    // ── 10. 원문 마스킹 ───────────────────────────────────────────────────

    @Test
    fun `원문을 보낼 때 고객명 줄이 마스킹된다`() {
        val rule = PhoneRule.defaultKbCardRule("2026-09-14T00:00:00+09:00").copy(
            enabled = true,
            match = RuleMatch(rawOnly = true, exclude = listOf("인증번호")),
        )
        val input = RuleInput(
            channel = "sms",
            sender = "15881688",
            text = sample,
            receivedAtMillis = receivedAt,
            dedupKey = "test-raw",
        )

        val preview = RuleEngine.preview(rule, input)

        assertTrue(preview.matched)
        val raw = preview.fields["text"] as String
        assertTrue("고객명이 남아 있습니다: $raw", !raw.contains("김*만"))
        assertTrue("고객명 줄이 남아 있습니다: $raw", !raw.contains("고객명"))
        assertTrue("본문은 남아야 합니다", raw.contains("제주화로집 신논현점"))
    }
}
