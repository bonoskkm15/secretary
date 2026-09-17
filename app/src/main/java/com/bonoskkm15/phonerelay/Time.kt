package com.bonoskkm15.phonerelay.core

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object Time {
    val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun nowIso(): String = format(OffsetDateTime.now(SEOUL))

    fun format(t: OffsetDateTime): String = t.truncatedTo(ChronoUnit.MILLIS).format(ISO)
}
