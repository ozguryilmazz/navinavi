package com.ozguryilmaz.navinavi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Kullanıcıya gösterilebilecek Türkçe mesajlı hata. */
open class TomTomException(message: String) : Exception(message)

/** HTTP 400: istek TomTom'a uymadı (ör. desteklenmeyen parametre ya da rota yok). */
class TomTomBadRequest(message: String) : TomTomException(message)

/** Rotanın bir kısmındaki yasal hız sınırı (rota başından metre olarak). */
data class SpeedLimitSection(val startMeters: Double, val endMeters: Double, val kmh: Int)

data class Place(val name: String, val address: String, val position: LatLng)

data class Instruction(
    val routeOffsetMeters: Int,
    val maneuver: String,
    val message: String,
    val point: LatLng,
    val roundaboutExit: Int?,
)

data class Route(
    val points: List<LatLng>,
    val lengthMeters: Int,
    val travelTimeSeconds: Int,
    val trafficDelaySeconds: Int,
    val instructions: List<Instruction>,
    /** Yasal hız sınırları; TomTom vermezse boş. */
    val speedLimits: List<SpeedLimitSection> = emptyList(),
) {
    /** Rota üzerinde bu noktadaki hız sınırı (km/sa), bilinmiyorsa null. */
    fun speedLimitAt(offsetMeters: Double): Int? =
        speedLimits.firstOrNull { offsetMeters >= it.startMeters && offsetMeters <= it.endMeters }?.kmh
}

/**
 * TomTom Search ve Routing API'leri. Ücretsiz planda günde 2.500 istek var;
 * yazarken arama yapıldığı için çağıran taraf beklemeli (debounce) ve önbelleğe almalı.
 */
class TomTomApi(private val apiKeys: ApiKeys) {

    /** typeahead=true: yazılmakta olan, yarım kalmış kelimeleri de tamamlayarak arar. */
    suspend fun search(query: String, near: LatLng?): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val params = buildString {
            append("countrySet=TR&language=tr-TR&limit=20&typeahead=true")
            if (near != null) append("&lat=${fmt(near.latitude)}&lon=${fmt(near.longitude)}")
        }
        val json = get("https://api.tomtom.com/search/2/search/$q.json?$params")
        val results = json.getJSONArray("results")
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            val address = r.optJSONObject("address")?.optString("freeformAddress").orEmpty()
            val poiName = r.optJSONObject("poi")?.optString("name").orEmpty()
            val pos = r.getJSONObject("position")
            Place(
                name = poiName.ifEmpty { address },
                address = if (poiName.isEmpty()) "" else address,
                position = LatLng(pos.getDouble("lat"), pos.getDouble("lon")),
            )
        }
    }

    /** Adres metnini koordinata çevirir (en iyi tek sonuç). Bulamazsa null. */
    suspend fun geocode(address: String, near: LatLng?): Place? {
        val q = URLEncoder.encode(address.trim(), "UTF-8").replace("+", "%20")
        val params = buildString {
            append("countrySet=TR&language=tr-TR&limit=1")
            if (near != null) append("&lat=${fmt(near.latitude)}&lon=${fmt(near.longitude)}")
        }
        val results = get("https://api.tomtom.com/search/2/geocode/$q.json?$params").getJSONArray("results")
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)
        val pos = r.getJSONObject("position")
        return Place(
            name = address,
            address = r.optJSONObject("address")?.optString("freeformAddress").orEmpty(),
            position = LatLng(pos.getDouble("lat"), pos.getDouble("lon")),
        )
    }

    suspend fun route(from: LatLng, to: LatLng): Route {
        val locations = "${fmt(from.latitude)},${fmt(from.longitude)}:${fmt(to.latitude)},${fmt(to.longitude)}"
        val params = "traffic=true&travelMode=car&instructionsType=tagged&language=tr-TR" +
            "&routeRepresentation=polyline&sectionType=traffic"
        val base = "https://api.tomtom.com/routing/1/calculateRoute/$locations/json?$params"
        // Hız sınırları (hız önerisi için) ayrı bölüm olarak istenir. TomTom bunu reddederse
        // rota yine de sınırsız haliyle alınır, navigasyon aksamaz.
        val json = try {
            get("$base&sectionType=speedLimit")
        } catch (e: TomTomBadRequest) {
            get(base)
        }
        val routes = json.optJSONArray("routes")
        if (routes == null || routes.length() == 0) throw TomTomException("Bu noktaya rota bulunamadı.")
        val r = routes.getJSONObject(0)

        val summary = r.getJSONObject("summary")
        val points = mutableListOf<LatLng>()
        val legs = r.getJSONArray("legs")
        for (i in 0 until legs.length()) {
            val pts = legs.getJSONObject(i).getJSONArray("points")
            for (j in 0 until pts.length()) {
                val p = pts.getJSONObject(j)
                points += LatLng(p.getDouble("latitude"), p.getDouble("longitude"))
            }
        }

        val instructions = mutableListOf<Instruction>()
        val ins = r.optJSONObject("guidance")?.optJSONArray("instructions")
        if (ins != null) {
            for (i in 0 until ins.length()) {
                val o = ins.getJSONObject(i)
                val p = o.getJSONObject("point")
                instructions += Instruction(
                    routeOffsetMeters = o.optInt("routeOffsetInMeters"),
                    maneuver = o.optString("maneuver"),
                    message = o.optString("message"),
                    point = LatLng(p.getDouble("latitude"), p.getDouble("longitude")),
                    roundaboutExit = if (o.has("roundaboutExitNumber")) o.getInt("roundaboutExitNumber") else null,
                )
            }
        }

        // Hız sınırı bölümleri nokta numarasıyla gelir; rota boyunca metreye çevrilir.
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + Navigator.distance(points[i - 1], points[i])
        val limits = mutableListOf<SpeedLimitSection>()
        val sections = r.optJSONArray("sections")
        if (sections != null) {
            for (i in 0 until sections.length()) {
                val sec = sections.getJSONObject(i)
                if (sec.optString("sectionType") != "SPEED_LIMIT") continue
                val kmh = sec.optInt("maxSpeedLimitInKmh", 0)
                val a = sec.optInt("startPointIndex", -1)
                val b = sec.optInt("endPointIndex", -1)
                if (kmh <= 0 || a !in points.indices || b !in points.indices) continue
                limits += SpeedLimitSection(cumulative[a], cumulative[b], kmh)
            }
        }

        return Route(
            points = points,
            lengthMeters = summary.optInt("lengthInMeters"),
            travelTimeSeconds = summary.optInt("travelTimeInSeconds"),
            trafficDelaySeconds = summary.optInt("trafficDelayInSeconds"),
            instructions = instructions,
            speedLimits = limits,
        )
    }

    private suspend fun get(urlWithoutKey: String): JSONObject = withContext(Dispatchers.IO) {
        val key = apiKeys.tomtom
        if (key.isEmpty()) throw TomTomException("TomTom anahtarı girilmemiş. Üstteki \"Anahtar\" butonuna basın.")
        val conn = (URL("$urlWithoutKey&key=${URLEncoder.encode(key, "UTF-8")}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        try {
            when (val code = conn.responseCode) {
                200 -> JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                400 -> throw TomTomBadRequest("Bu noktaya rota bulunamadı.")
                401, 403 -> throw TomTomException(
                    "TomTom isteği reddetti ($code). Günlük ücretsiz kota dolmuş ya da anahtar hatalı olabilir. " +
                        "Kota her gün sıfırlanır; ücret çıkmaz."
                )
                429 -> throw TomTomException("TomTom'a çok sık istek gönderildi, birkaç saniye sonra tekrar deneyin.")
                else -> throw TomTomException("TomTom hatası: HTTP $code")
            }
        } catch (e: IOException) {
            throw TomTomException("İnternet bağlantısı kurulamadı.")
        } finally {
            conn.disconnect()
        }
    }

    private fun fmt(d: Double) = String.format(Locale.US, "%.6f", d)
}
