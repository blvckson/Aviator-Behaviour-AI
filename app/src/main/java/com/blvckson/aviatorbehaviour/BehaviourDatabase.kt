package com.blvckson.aviatorbehaviour

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class StoredBehaviour(
    val round: Int,
    val time: Long,
    val maxSimilarity: Double,
    val avgSimilarity: Double,
    val similaritySamples: Int,
    val maxVisualChange: Double,
    val maxPlaneMotion: Double,
    val maxUltraWatch: Double,
    val preFlyAwayMaxSimilarity: Double,
    val preFlyAwayMaxVisualChange: Double,
    val preFlyAwayMaxPlaneMotion: Double,
    val preFlyAwayMaxUltraWatch: Double
)

class BehaviourDatabase(context: Context) :
    SQLiteOpenHelper(context, "behaviour_memory.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE rounds (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                round_no INTEGER NOT NULL,
                time_ms INTEGER NOT NULL,
                max_similarity REAL NOT NULL,
                avg_similarity REAL NOT NULL,
                similarity_samples INTEGER NOT NULL,
                max_visual_change REAL NOT NULL,
                max_plane_motion REAL NOT NULL,
                max_ultrawatch REAL NOT NULL,
                pre_max_similarity REAL NOT NULL,
                pre_max_visual_change REAL NOT NULL,
                pre_max_plane_motion REAL NOT NULL,
                pre_max_ultrawatch REAL NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_round_time ON rounds(time_ms DESC)")
        db.execSQL("CREATE INDEX idx_round_number ON rounds(round_no DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS rounds")
        onCreate(db)
    }

    fun nextRoundNumber(): Int {
        readableDatabase.rawQuery("SELECT COALESCE(MAX(round_no), 0) + 1 FROM rounds", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 1
        }
    }

    @Synchronized
    fun saveRound(r: StoredBehaviour) {
        val values = ContentValues().apply {
            put("round_no", r.round)
            put("time_ms", r.time)
            put("max_similarity", r.maxSimilarity)
            put("avg_similarity", r.avgSimilarity)
            put("similarity_samples", r.similaritySamples)
            put("max_visual_change", r.maxVisualChange)
            put("max_plane_motion", r.maxPlaneMotion)
            put("max_ultrawatch", r.maxUltraWatch)
            put("pre_max_similarity", r.preFlyAwayMaxSimilarity)
            put("pre_max_visual_change", r.preFlyAwayMaxVisualChange)
            put("pre_max_plane_motion", r.preFlyAwayMaxPlaneMotion)
            put("pre_max_ultrawatch", r.preFlyAwayMaxUltraWatch)
        }
        writableDatabase.insert("rounds", null, values)
    }

    fun countRounds(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM rounds", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun latest(limit: Int = 40): List<StoredBehaviour> {
        val list = ArrayList<StoredBehaviour>()
        readableDatabase.query(
            "rounds", null, null, null, null, null,
            "round_no DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) list.add(read(c))
        }
        return list
    }

    fun latestPreFlyAway(limit: Int = 40): List<StoredBehaviour> {
        val list = ArrayList<StoredBehaviour>()
        readableDatabase.query(
            "rounds", null,
            "pre_max_similarity > 0 OR pre_max_visual_change > 0 OR pre_max_ultrawatch > 0",
            null, null, null, "round_no DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) list.add(read(c))
        }
        return list
    }

    @Synchronized
    fun clearAll() {
        writableDatabase.delete("rounds", null, null)
    }

    private fun read(c: android.database.Cursor): StoredBehaviour {
        return StoredBehaviour(
            c.getInt(c.getColumnIndexOrThrow("round_no")),
            c.getLong(c.getColumnIndexOrThrow("time_ms")),
            c.getDouble(c.getColumnIndexOrThrow("max_similarity")),
            c.getDouble(c.getColumnIndexOrThrow("avg_similarity")),
            c.getInt(c.getColumnIndexOrThrow("similarity_samples")),
            c.getDouble(c.getColumnIndexOrThrow("max_visual_change")),
            c.getDouble(c.getColumnIndexOrThrow("max_plane_motion")),
            c.getDouble(c.getColumnIndexOrThrow("max_ultrawatch")),
            c.getDouble(c.getColumnIndexOrThrow("pre_max_similarity")),
            c.getDouble(c.getColumnIndexOrThrow("pre_max_visual_change")),
            c.getDouble(c.getColumnIndexOrThrow("pre_max_plane_motion")),
            c.getDouble(c.getColumnIndexOrThrow("pre_max_ultrawatch"))
        )
    }
}
