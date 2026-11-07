package com.ozguryilmaz.navinavi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** OpenStreetMap / Overpass API'den rota boyunca `highway=traffic_signals` düğümlerini bulur. Ücretsiz, anahtarsız. */
object TrafficLights {

    /** Ana sunucu yoğunken 502/504 verebiliyor; o zaman sıradakine geçilir. */
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )
    /** Rota çizgisine bu kadar yakın ışıklar alınır (m). */
    private const val NEAR_ROUTE_M = 20.0
    /** Aynı kavşaktaki birden fazla ışık düğümü tek ışık sayılır (rota boyunca m). */
    private const val SAME_JUNCTION_M = 40.0
    /** Çok uzun rotalarda sadece baştaki kısım sorulur; yeniden rotada tekrar çekilir. */
    private const val MAX_QUERY_M = 60_000.0

    suspend fun along(route: Route): List<RouteLight> {
        val nav = Navigator(route)
        val line = simplify(prefix(route.points, MAX_QUERY_M), 8.0)
        if (line.size < 2) return emptyList()
        val nodes = query(line)
        val matched = nodes.mapNotNull { (id, pos) ->
            val rp = nav.locate(pos) ?: return@mapNotNull null
            if (rp.distanceFromRoute > NEAR_ROUTE_M) return@mapNotNull null
            // Yön 4 dilime ayrılır: 0=kuzey, 1=doğu, 2=güney, 3=batı.
            val bucket = (((rp.bearing + 45) % 360) / 90).toInt()
            RouteLight("$id:$bucket", id, pos, rp.offset)
        }.sortedBy { it.routeOffset }

        val merged = mutableListOf<RouteLight>()
        for (l in matched) {
            val last = merged.lastOrNull()
            if (last == null || l.routeOffset - last.routeOffset > SAME_JUNCTION_M) merged += l
        }
        return merged
    }

    private suspend fun query(line: List<LatLng>): List<Pair<Long, LatLng>> = withContext(Dispatchers.IO) {
        val coords = line.joinToString(",") { String.format(Locale.US, "%.5f,%.5f", it.latitude, it.longitude) }
        val ql = "[out:json][timeout:25];node(around:${NEAR_ROUTE_M.toInt() + 5},$coords)[highway=traffic_signals];out;"
        var error: Exception? = null
        for (url in ENDPOINTS) {
            try {
                return@withContext post(url, ql)
            } catch (e: Exception) {
                error = e
            }
        }
        throw error ?: java.io.IOException("Overpass")
    }

    private fun post(endpoint: String, ql: String): List<Pair<Long, LatLng>> {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "NaviNavi/${BuildConfig.VERSION_NAME}")
        }
        return try {
            conn.outputStream.use { it.write("data=${URLEncoder.encode(ql, "UTF-8")}".toByteArray()) }
            if (conn.responseCode != 200) throw java.io.IOException("Overpass HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val els = json.getJSONArray("elements")
            (0 until els.length()).map { i ->
                val e = els.getJSONObject(i)
                e.getLong("id") to LatLng(e.getDouble("lat"), e.getDouble("lon"))
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

    /** Douglas–Peucker: çizgiyi tolerans (m) içinde kalarak az noktaya indirir (istek kısalsın). */
    private fun simplify(points: List<LatLng>, toleranceM: Double): List<LatLng> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var maxD = 0.0
            var idx = -1
            for (i in s + 1 until e) {
                val d = pointToSegment(points[i], points[s], points[e])
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (idx >= 0 && maxD > toleranceM) {
                keep[idx] = true
                stack.addLast(s to idx)
                stack.addLast(idx to e)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun pointToSegment(p: LatLng, a: LatLng, b: LatLng): Double {
        val k = kotlin.math.cos(Math.toRadians(a.latitude))
        val m = 111_320.0
        val bx = (b.longitude - a.longitude) * k * m
        val by = (b.latitude - a.latitude) * m
        val px = (p.longitude - a.longitude) * k * m
        val py = (p.latitude - a.latitude) * m
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
