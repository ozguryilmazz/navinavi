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
class LightStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "lights.db", null, 3) {

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
        createOsmTable(db)
        createChecksTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createOsmTable(db)
        if (oldVersion < 3) createChecksTable(db)
    }

    /** Tahmin isabet kontrolü: tahmin edilen an ile kullanıcının bastığı an arasındaki fark. */
    private fun createChecksTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS checks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                light_key TEXT NOT NULL,
                kind TEXT NOT NULL,
                error_ms INTEGER NOT NULL,
                time_ms INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS checks_light ON checks(light_key, time_ms)")
    }

    fun addCheck(lightKey: String, kind: LightRecord.Kind, errorMs: Long, timeMs: Long) {
        writableDatabase.insert("checks", null, ContentValues().apply {
            put("light_key", lightKey)
            put("kind", kind.name)
            put("error_ms", errorMs)
            put("time_ms", timeMs)
        })
    }

    /** Son kontrollerdeki hata (ms), yeniden eskiye. */
    fun checkErrorsFor(lightKey: String, limit: Int = 20): List<Long> {
        val list = mutableListOf<Long>()
        readableDatabase.rawQuery(
            "SELECT error_ms FROM checks WHERE light_key = ? ORDER BY time_ms DESC LIMIT $limit", arrayOf(lightKey),
        ).use { c -> while (c.moveToNext()) list += c.getLong(0) }
        return list
    }

    /** Bütün kayıtlar ve tahmin kontrolleri, CSV (Excel'de açılır). Dönüş: (metin, kayıt sayısı, ışık sayısı). */
    fun exportCsv(): Triple<String, Int, Int> {
        val sb = StringBuilder()
        sb.append("tur,isik,osm_dugum,enlem,boylam,renk,zaman,zaman_ms,kaynak_veya_hata_ms\n")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        var n = 0
        val lights = HashSet<String>()
        readableDatabase.rawQuery(
            "SELECT light_key, node_id, lat, lon, kind, time_ms, source FROM records ORDER BY light_key, time_ms", null,
        ).use { c ->
            while (c.moveToNext()) {
                val t = c.getLong(5)
                sb.append("kayit,").append(c.getString(0)).append(',').append(c.getLong(1)).append(',')
                    .append(c.getDouble(2)).append(',').append(c.getDouble(3)).append(',')
                    .append(c.getString(4)).append(',').append(fmt.format(java.util.Date(t))).append(',')
                    .append(t).append(',').append(c.getString(6)).append('\n')
                n++
                lights += c.getString(0)
            }
        }
        readableDatabase.rawQuery("SELECT light_key, kind, error_ms, time_ms FROM checks ORDER BY time_ms", null).use { c ->
            while (c.moveToNext()) {
                val t = c.getLong(3)
                sb.append("kontrol,").append(c.getString(0)).append(",,,,").append(c.getString(1)).append(',')
                    .append(fmt.format(java.util.Date(t))).append(',').append(t).append(',').append(c.getLong(2)).append('\n')
            }
        }
        return Triple(sb.toString(), n, lights.size)
    }

    /** OSM'den gelen ışık konumları: aynı yolda bir dahakine sunucuyu beklemeden çıksın. */
    private fun createOsmTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS osm_lights (
                node_id INTEGER PRIMARY KEY,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                seen_ms INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS osm_lights_pos ON osm_lights(lat, lon)")
    }

    fun saveOsm(nodes: List<OsmLight>) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (n in nodes) {
                db.insertWithOnConflict("osm_lights", null, ContentValues().apply {
                    put("node_id", n.id)
                    put("lat", n.position.latitude)
                    put("lon", n.position.longitude)
                    put("seen_ms", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Kutulardan herhangi birinin içindeki saklı OSM ışıkları. */
    fun osmIn(boxes: List<Box>): List<OsmLight> {
        val found = LinkedHashMap<Long, OsmLight>()
        for (b in boxes) {
            readableDatabase.rawQuery(
                "SELECT node_id, lat, lon FROM osm_lights WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
                arrayOf(b.s.toString(), b.n.toString(), b.w.toString(), b.e.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    found[id] = OsmLight(id, org.maplibre.android.geometry.LatLng(c.getDouble(1), c.getDouble(2)))
                }
            }
        }
        return found.values.toList()
    }

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
