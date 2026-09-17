package com.bonoskkm15.phonerelay.rule

import android.content.Context
import com.bonoskkm15.phonerelay.core.Time
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 규칙 레시피 저장소. 서버 토큰이나 수집 원문은 여기에 별도로 저장하지 않는다. */
object RuleStore {
    private const val PREFS = "rule_store"
    private const val KEY_RULES = "rules"

    fun list(ctx: Context): List<PhoneRule> = synchronized(this) {
        val prefs = prefs(ctx)
        val raw = prefs.getString(KEY_RULES, null)
        if (raw.isNullOrBlank()) {
            val preset = PhoneRule.defaultKbCardRule(Time.nowIso())
            save(ctx, listOf(preset))
            return@synchronized listOf(preset)
        }
        parseArray(raw)
    }

    fun get(ctx: Context, id: String): PhoneRule? = list(ctx).firstOrNull { it.id == id }

    fun upsert(ctx: Context, rule: PhoneRule) = synchronized(this) {
        val updated = list(ctx).filterNot { it.id == rule.id } + rule
        save(ctx, updated)
    }

    fun remove(ctx: Context, id: String) = synchronized(this) {
        val remaining = list(ctx).filterNot { it.id == id }
        if (remaining.isEmpty()) {
            save(ctx, listOf(PhoneRule.defaultKbCardRule(Time.nowIso())))
        } else {
            save(ctx, remaining)
        }
    }

    fun duplicate(ctx: Context, id: String): PhoneRule? = synchronized(this) {
        val original = list(ctx).firstOrNull { it.id == id } ?: return@synchronized null
        val now = Time.nowIso()
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            name = "${original.name} 복사",
            enabled = false,
            createdAt = now,
            updatedAt = now,
            lastMatchedAt = null,
            todayMatchCount = 0,
            matchCountDate = null,
        )
        save(ctx, list(ctx) + copy)
        copy
    }

    fun setEnabled(ctx: Context, id: String, enabled: Boolean) = synchronized(this) {
        save(ctx, list(ctx).map { if (it.id == id) it.copy(enabled = enabled, updatedAt = Time.nowIso()) else it })
    }

    fun recordMatch(ctx: Context, id: String, matchedAt: String = Time.nowIso()) = synchronized(this) {
        val date = matchedAt.take(10)
        save(ctx, list(ctx).map {
            if (it.id != id) it else it.copy(
                lastMatchedAt = matchedAt,
                todayMatchCount = if (it.matchCountDate == date) it.todayMatchCount + 1 else 1,
                matchCountDate = date,
            )
        })
    }

    fun exportJson(ctx: Context): String = synchronized(this) {
        val array = JSONArray()
        list(ctx).forEach { array.put(it.toJson()) }
        array.toString(2)
    }

    /** JSON 배열 또는 {"rules": [...]} 형태를 가져온다. */
    fun importJson(ctx: Context, raw: String, replace: Boolean = false): Int = synchronized(this) {
        val array = try {
            val trimmed = raw.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONObject(trimmed).optJSONArray("rules") ?: JSONArray()
        } catch (e: Exception) {
            throw IllegalArgumentException("규칙 JSON을 읽을 수 없습니다", e)
        }
        val imported = buildList {
            for (i in 0 until array.length()) {
                PhoneRule.fromJson(array.optJSONObject(i) ?: continue)?.let(::add)
            }
        }
        if (imported.isEmpty()) throw IllegalArgumentException("가져올 규칙이 없습니다")
        val base = if (replace) emptyList() else list(ctx)
        save(ctx, (base + imported).distinctBy { it.id })
        imported.size
    }

    private fun parseArray(raw: String): List<PhoneRule> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                PhoneRule.fromJson(array.optJSONObject(i) ?: continue)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun save(ctx: Context, rules: List<PhoneRule>) {
        val array = JSONArray()
        rules.forEach { array.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_RULES, array.toString()).commit()
    }
}
