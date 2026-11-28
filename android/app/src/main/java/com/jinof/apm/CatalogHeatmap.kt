package com.jinof.apm

import android.database.sqlite.SQLiteDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class PreparedHeatmap(val initialYear: Int, val years: Map<Int, PhotoHeatmapYear>)

/** A committed change of dated catalog membership. A date correction emits a removal and an addition. */
private data class CaptureDateEvent(val capturedAt: Long, val delta: Int)

/** The writer delivers events to the daily counter in the same SQLite transaction as its catalog changes. */
internal class CatalogHeatmap {
    private var revision = -1L
    private var previousDays = emptyMap<Int, Map<LocalDate, Int>>()
    private var prepared: PreparedHeatmap? = null

    companion object {
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS picker_locations(uri TEXT PRIMARY KEY)")
            db.execSQL("""INSERT OR IGNORE INTO picker_locations
                SELECT uri FROM media_locations WHERE uri NOT LIKE 'content://media/%/images/media/%'""")
            db.execSQL("CREATE TABLE IF NOT EXISTS heatmap_days(day TEXT PRIMARY KEY, count INTEGER NOT NULL CHECK(count >= 0))")
            db.execSQL("CREATE TABLE IF NOT EXISTS heatmap_state(id INTEGER PRIMARY KEY CHECK(id = 1), zone TEXT NOT NULL, revision INTEGER NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO heatmap_state VALUES(1, '', 0)")
        }
    }

    /** A null ID set denotes the full MediaStore visibility scan. */
    fun <T> track(db: SQLiteDatabase, affected: Set<String>?, write: () -> T): T {
        val zone = ZoneId.systemDefault()
        ensureZone(db, zone)
        val before = captures(db, affected)
        val result = write()
        val after = captures(db, affected)
        val events = sequence {
            for ((id, time) in before) if (after[id] != time) yield(CaptureDateEvent(time, -1))
            for ((id, time) in after) if (before[id] != time) yield(CaptureDateEvent(time, 1))
        }
        consume(db, zone, events)
        return result
    }

    private fun captures(db: SQLiteDatabase, ids: Set<String>?): Map<String, Long> = buildMap {
        fun read(batch: List<String>?) {
            val filter = batch?.let { "AND photo_id IN (${it.joinToString(",") { "?" }})" }.orEmpty()
            val sql = """
                SELECT location.photo_id, location.date_taken_millis
                FROM (
                    SELECT MIN(uri) AS uri FROM media_locations
                    WHERE accessible = 1 $filter GROUP BY photo_id
                ) AS chosen JOIN media_locations AS location ON chosen.uri = location.uri
                WHERE location.date_taken_millis IS NOT NULL
            """.trimIndent()
            db.rawQuery(sql, batch?.toTypedArray()).use { c ->
                while (c.moveToNext()) put(c.getString(0), c.getLong(1))
            }
        }
        if (ids == null) read(null) else ids.chunked(800).forEach(::read)
    }

    private fun consume(db: SQLiteDatabase, zone: ZoneId, events: Sequence<CaptureDateEvent>) {
        val changes = mutableMapOf<LocalDate, Int>()
        for (event in events) {
            val day = Instant.ofEpochMilli(event.capturedAt).atZone(zone).toLocalDate()
            changes[day] = changes.getOrDefault(day, 0) + event.delta
        }
        val net = changes.filterValues { it != 0 }
        if (net.isEmpty()) return
        for ((day, delta) in net) {
            val key = day.toString()
            db.execSQL("INSERT OR IGNORE INTO heatmap_days VALUES(?, 0)", arrayOf(key))
            db.execSQL("UPDATE heatmap_days SET count = count + ? WHERE day = ?", arrayOf(delta, key))
            if (delta < 0) db.delete("heatmap_days", "day = ? AND count = 0", arrayOf(key))
        }
        db.execSQL("UPDATE heatmap_state SET revision = revision + 1 WHERE id = 1")
    }

    private fun ensureZone(db: SQLiteDatabase, zone: ZoneId) {
        val stored = db.rawQuery("SELECT zone FROM heatmap_state WHERE id = 1", null).use { c ->
            c.moveToFirst()
            c.getString(0)
        }
        if (stored == zone.id) return
        db.delete("heatmap_days", null, null)
        consume(db, zone, captures(db, null).values.asSequence().map { CaptureDateEvent(it, 1) })
        db.execSQL("UPDATE heatmap_state SET zone = ?, revision = revision + 1 WHERE id = 1", arrayOf(zone.id))
    }

    /** Called on the catalog worker. Page entry only reads this immutable result. */
    fun prepare(db: SQLiteDatabase, zone: ZoneId): PreparedHeatmap {
        db.beginTransactionNonExclusive()
        val currentRevision: Long
        val days = mutableMapOf<Int, MutableMap<LocalDate, Int>>()
        val currentYear = LocalDate.now(zone).year
        try {
            ensureZone(db, zone)
            currentRevision = db.rawQuery("SELECT revision FROM heatmap_state WHERE id = 1", null).use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
            prepared?.let { cached ->
                if (currentRevision == revision && (previousDays.isNotEmpty() || currentYear == cached.initialYear)) {
                    db.setTransactionSuccessful()
                    return cached
                }
            }
            db.rawQuery("SELECT day, count FROM heatmap_days", null).use { c ->
                while (c.moveToNext()) {
                    val date = LocalDate.parse(c.getString(0))
                    days.getOrPut(date.year) { mutableMapOf() }[date] = c.getInt(1)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val first = days.keys.maxOrNull() ?: currentYear
        val years = (days.keys + first).associateWith { year ->
            val counts = days[year].orEmpty()
            prepared?.years?.get(year)?.takeIf { previousDays[year].orEmpty() == counts }
                ?: PhotoWallOrganizer.heatmap(year, counts)
        }
        previousDays = days
        revision = currentRevision
        return PreparedHeatmap(first, years).also { prepared = it }
    }
}
