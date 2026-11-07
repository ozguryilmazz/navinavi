package com.ozguryilmaz.navinavi

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Rota üzerindeki bir trafik ışığı.
 * key: kayıtların bağlandığı kimlik = OSM düğümü + yaklaşma yönü (aynı kavşağa farklı yönden
 * gelince yeşil süresi farklı olur, o yüzden ayrı öğrenilir).
 */
data class RouteLight(
    val key: String,
    val nodeId: Long,
    val position: LatLng,
    /** Rota başından ışığa rota boyunca mesafe (m). */
    val routeOffset: Double,
)

/** OSM'deki bir `highway=traffic_signals` düğümü. */
data class OsmLight(val id: Long, val position: LatLng)

/** Enlem/boylam kutusu: güney, batı, kuzey, doğu. */
data class Box(val s: Double, val w: Double, val n: Double, val e: Double)

/**
 * OpenStreetMap / Overpass API'den rota çevresindeki trafik ışıklarını bulur. Ücretsiz, anahtarsız.
 * Ücretsiz sunucular zaman zaman çok yavaşlıyor; o yüzden hepsine aynı anda sorulur, ilk gelen
 * cevap alınır. Gelen ışıklar telefonda saklanır (LightStore), aynı yolda bir dahakine anında çıkar.
 */
object TrafficLights {

    private const val TAG = "TrafficLights"
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )
    /** Rota çizgisine bu kadar yakın ışıklar alınır (m). */
    private const val NEAR_ROUTE_M = 20.0
    /** Aynı kavşaktaki birden fazla ışık düğümü tek ışık sayılır (rota boyunca m). */
    private const val SAME_JUNCTION_M = 40.0
    /** Çok uzun rotalarda sadece baştaki kısım sorulur; yeniden rotada tekrar çekilir. */
    private const val MAX_QUERY_M = 60_000.0
    /** Rota bu uzunlukta parçalara bölünür, her parçanın çevresindeki kutu sorulur (m). */
    private const val CHUNK_M = 3_000.0
    /** Kutuların rota çevresinde bıraktığı pay (derece, ~60 m). */
    private const val MARGIN_DEG = 0.0006
    private const val TOTAL_TIMEOUT_MS = 70_000L

    /** Bağlantıları beklemek için ayrı kapsam: ilk cevap gelince öbürleri arka planda kendi kendine biter. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Rotanın sorulan kısmını kaplayan kutular. */
    fun boxes(route: Route): List<Box> {
        val pts = prefix(route.points, MAX_QUERY_M)
        if (pts.isEmpty()) return emptyList()
        val boxes = mutableListOf<Box>()
        var s = 90.0; var w = 180.0; var n = -90.0; var e = -180.0
        var len = 0.0
        for (i in pts.indices) {
            val p = pts[i]
            s = minOf(s, p.latitude); n = maxOf(n, p.latitude)
            w = minOf(w, p.longitude); e = maxOf(e, p.longitude)
            if (i > 0) len += Navigator.distance(pts[i - 1], p)
            if (len >= CHUNK_M || i == pts.lastIndex) {
                boxes += Box(s - MARGIN_DEG, w - MARGIN_DEG, n + MARGIN_DEG, e + MARGIN_DEG)
                // Sonraki parça bu noktadan başlasın, aralarında boşluk kalmasın.
                s = p.latitude; n = p.latitude; w = p.longitude; e = p.longitude
                len = 0.0
            }
        }
        return boxes
    }

    /** Sunuculardan ışıkları çeker. Hepsi başarısız olursa ya da süre dolarsa hata fırlatır. */
    suspend fun fetch(route: Route): List<OsmLight> {
        val boxes = boxes(route)
        if (boxes.isEmpty()) return emptyList()
        val parts = boxes.joinToString("") {
            String.format(Locale.US, "node[highway=traffic_signals](%.5f,%.5f,%.5f,%.5f);", it.s, it.w, it.n, it.e)
        }
        val ql = "[out:json][timeout:60];($parts);out skel;"
        val results = Channel<Result<List<OsmLight>>>(ENDPOINTS.size)
        for (url in ENDPOINTS) io.launch { results.send(runCatching { post(url, ql) }) }
        var error: Throwable? = null
        return withTimeout(TOTAL_TIMEOUT_MS) {
            repeat(ENDPOINTS.size) {
                val r = results.receive()
                r.onSuccess { return@withTimeout it }
                error = r.exceptionOrNull()
                Log.w(TAG, "Overpass sunucusu cevap vermedi", error)
            }
            throw error ?: java.io.IOException("Overpass")
        }
    }

    /** OSM ışıklarından rota üzerinde olanları seçer, rota boyunca sıralar. */
    fun match(route: Route, nodes: Collection<OsmLight>): List<RouteLight> {
        if (nodes.isEmpty()) return emptyList()
        val nav = Navigator(route)
        val matched = nodes.distinctBy { it.id }.mapNotNull { node ->
            val rp = nav.locate(node.position) ?: return@mapNotNull null
            if (rp.distanceFromRoute > NEAR_ROUTE_M) return@mapNotNull null
            // Yön 4 dilime ayrılır: 0=kuzey, 1=doğu, 2=güney, 3=batı.
            val bucket = (((rp.bearing + 45) % 360) / 90).toInt()
            RouteLight("${node.id}:$bucket", node.id, node.position, rp.offset)
        }.sortedBy { it.routeOffset }

        val merged = mutableListOf<RouteLight>()
        for (l in matched) {
            val last = merged.lastOrNull()
            if (last == null || l.routeOffset - last.routeOffset > SAME_JUNCTION_M) merged += l
        }
        return merged
    }

    private fun post(endpoint: String, ql: String): List<OsmLight> {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 65_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "NaviNavi/${BuildConfig.VERSION_NAME} (kisisel navigasyon)")
        }
        return try {
            conn.outputStream.use { it.write("data=${URLEncoder.encode(ql, "UTF-8")}".toByteArray()) }
            if (conn.responseCode != 200) throw java.io.IOException("Overpass HTTP ${conn.responseCode} ($endpoint)")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            // Sunucu süre aşımında 200 ile "remark" döndürüp listeyi yarım bırakabiliyor.
            json.optString("remark").takeIf { it.contains("timed out") || it.contains("error") }?.let {
                throw java.io.IOException("Overpass: $it")
            }
            val els = json.getJSONArray("elements")
            (0 until els.length()).map { i ->
                val e = els.getJSONObject(i)
                OsmLight(e.getLong("id"), LatLng(e.getDouble("lat"), e.getDouble("lon")))
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun prefix(points: List<LatLng>, maxMeters: Double): List<LatLng> {
        var total = 0.0
        for (i in 1 until points.size) {
            total += Navigator.distance(points[i - 1], points[i])
            if (total > maxMeters) return points.subList(0, i + 1)
        }
        return points
    }
}
