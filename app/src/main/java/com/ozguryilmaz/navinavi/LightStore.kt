package com.ozguryilmaz.navinavi

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Bir ışık kaydı: yeşil ya da kırmızının yandığı an. */
data class LightRecord(
    val lightKey: String,
    val kind: Kind,
    /** UTC milisaniye, mümkünse GPS saatinden. */
    val timeMs: Long,
    /** USER = butona basıldı (kesin), AUTO = dur-kalktan tahmin (yaklaşık). */
    val source: Source,
) {
    enum class Kind { GREEN, RED }
    enum class Source { USER, AUTO }
}

/**
 * Trafik ışığı kayıtları telefonda SQLite'ta tutulur (tahmin bu kayıtlardan öğrenilecek).
 * Room yerine düz SQLite: ek derleme eklentisi (KSP) gerektirmiyor, tek tablo için yeterli.
 */
class LightStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "lights.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                light_key TEXT NOT NULL,
                node_id INTEGER NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                kind TEXT NOT NULL,
                time_ms INTEGER NOT NULL,
                source TEXT NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX records_light ON records(light_key, time_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun add(light: RouteLight, kind: LightRecord.Kind, timeMs: Long, source: LightRecord.Source) {
        writableDatabase.insert("records", null, ContentValues().apply {
            put("light_key", light.key)
            put("node_id", light.nodeId)
            put("lat", light.position.latitude)
            put("lon", light.position.longitude)
            put("kind", kind.name)
            put("time_ms", timeMs)
            put("source", source.name)
        })
    }

    fun recordsFor(lightKey: String): List<LightRecord> {
        val list = mutableListOf<LightRecord>()
        readableDatabase.rawQuery(
            "SELECT kind, time_ms, source FROM records WHERE light_key = ? ORDER BY time_ms", arrayOf(lightKey),
        ).use { c ->
            while (c.moveToNext()) {
                list += LightRecord(
                    lightKey,
                    LightRecord.Kind.valueOf(c.getString(0)),
                    c.getLong(1),
                    LightRecord.Source.valueOf(c.getString(2)),
                )
            }
        }
        return list
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM records", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }
}
