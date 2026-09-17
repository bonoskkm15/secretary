package com.bonoskkm15.phonerelay.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 로컬 전송 큐. 수집한 이벤트는 반드시 여기에 먼저 저장하고, [SendWorker]가 비운다.
 * dedup_key가 같은 이벤트는 한 번만 들어간다(같은 문자를 두 경로에서 잡는 경우 대비).
 */
class EventQueue private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "queue.db", null, 1) {

    data class Row(val eventId: String, val body: String)
    data class Stats(val pending: Int, val sent: Int, val failed: Int)
    data class Recent(
        val eventId: String,
        val source: String,
        val type: String,
        val status: Int,
        val createdAt: Long,
        val body: String,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE events (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id    TEXT NOT NULL UNIQUE,
                source      TEXT NOT NULL,
                type        TEXT NOT NULL,
                dedup_key   TEXT UNIQUE,
                body        TEXT NOT NULL,
                status      INTEGER NOT NULL DEFAULT 0,
                retries     INTEGER NOT NULL DEFAULT 0,
                created_at  INTEGER NOT NULL,
                sent_at     INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_events_status ON events(status, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** 새로 들어갔으면 true, 중복이면 false. */
    fun enqueue(ctx: Context, event: PhoneEvent, dedupKey: String? = null): Boolean {
        val cv = ContentValues().apply {
            put("event_id", event.eventId)
            put("source", event.source)
            put("type", event.type)
            put("dedup_key", dedupKey)
            put("body", event.toJson(DeviceInfo.of(ctx)).toString())
            put("status", PENDING)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict(
            "events", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun pending(limit: Int = BATCH_SIZE): List<Row> =
        readableDatabase.rawQuery(
            "SELECT event_id, body FROM events WHERE status = ? ORDER BY id LIMIT ?",
            arrayOf(PENDING.toString(), limit.toString())
        ).use { c ->
            buildList { while (c.moveToNext()) add(Row(c.getString(0), c.getString(1))) }
        }

    fun markSent(eventIds: Collection<String>) = inTx(eventIds) { db, id ->
        db.execSQL(
            "UPDATE events SET status = ?, sent_at = ? WHERE event_id = ?",
            arrayOf<Any>(SENT, System.currentTimeMillis(), id)
        )
    }

    /** 재시도 횟수를 올리고, 한도를 넘으면 FAILED로 둔다. */
    fun markRetry(eventIds: Collection<String>) = inTx(eventIds) { db, id ->
        db.execSQL(
            """
            UPDATE events
               SET retries = retries + 1,
                   status  = CASE WHEN retries + 1 >= ? THEN ? ELSE ? END
             WHERE event_id = ? AND status = ?
            """.trimIndent(),
            arrayOf<Any>(MAX_RETRIES, FAILED, PENDING, id, PENDING)
        )
    }

    fun retryFailed() {
        writableDatabase.execSQL(
            "UPDATE events SET status = ?, retries = 0 WHERE status = ?",
            arrayOf<Any>(PENDING, FAILED)
        )
    }

    fun prune(keepDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - keepDays * 24L * 3600 * 1000
        writableDatabase.execSQL(
            "DELETE FROM events WHERE status = ? AND sent_at < ?",
            arrayOf<Any>(SENT, cutoff)
        )
    }

    fun stats(): Stats = readableDatabase.rawQuery(
        "SELECT status, COUNT(*) FROM events GROUP BY status", null
    ).use { c ->
        var p = 0; var s = 0; var f = 0
        while (c.moveToNext()) when (c.getInt(0)) {
            PENDING -> p = c.getInt(1)
            SENT -> s = c.getInt(1)
            FAILED -> f = c.getInt(1)
        }
        Stats(p, s, f)
    }

    fun recent(limit: Int = 15): List<Recent> = readableDatabase.rawQuery(
        "SELECT event_id, source, type, status, created_at, body FROM events ORDER BY id DESC LIMIT ?",
        arrayOf(limit.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                Recent(
                    eventId = c.getString(0),
                    source = c.getString(1),
                    type = c.getString(2),
                    status = c.getInt(3),
                    createdAt = c.getLong(4),
                    body = c.getString(5),
                )
            )
        }
    }

    fun find(eventId: String): Recent? = readableDatabase.rawQuery(
        "SELECT event_id, source, type, status, created_at, body FROM events WHERE event_id = ?",
        arrayOf(eventId)
    ).use { c ->
        if (!c.moveToFirst()) null else Recent(
            eventId = c.getString(0),
            source = c.getString(1),
            type = c.getString(2),
            status = c.getInt(3),
            createdAt = c.getLong(4),
            body = c.getString(5),
        )
    }

    private inline fun inTx(ids: Collection<String>, block: (SQLiteDatabase, String) -> Unit) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { block(db, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val PENDING = 0
        const val SENT = 1
        const val FAILED = 2
        const val MAX_RETRIES = 20
        const val BATCH_SIZE = 50

        @Volatile private var instance: EventQueue? = null

        fun get(ctx: Context): EventQueue =
            instance ?: synchronized(this) {
                instance ?: EventQueue(ctx.applicationContext).also { instance = it }
            }
    }
}
