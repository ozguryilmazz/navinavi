package com.ozguryilmaz.navinavi

import org.maplibre.android.geometry.LatLng
import kotlin.math.cos
import kotlin.math.sqrt

/** Bir konum güncellemesinde rota üzerindeki durum. */
data class NavProgress(
    /** Konumun rota çizgisine oturtulmuş hali (basit map matching). */
    val snapped: LatLng,
    /** Rota çizgisine uzaklık (m). */
    val distanceFromRoute: Double,
    /** Başlangıçtan bu yana rota boyunca gidilen yol (m). */
    val traveled: Double,
    val remainingMeters: Double,
    val remainingSeconds: Int,
    /** Sıradaki manevra; varışa gelindiyse ARRIVE talimatı. */
    val next: Instruction?,
    val distanceToNext: Double,
    /** Ondan sonraki manevra (ör. "ardından sola"). */
    val afterNext: Instruction?,
    val arrived: Boolean,
)

data class RoutePoint(val distanceFromRoute: Double, val offset: Double, val bearing: Double)

/**
 * Rota üzerinde ilerlemeyi hesaplar: konumu en yakın rota parçasına izdüşürür,
 * gidilen yolu bulur ve sıradaki manevrayı seçer. Android'den bağımsız, saf hesap.
 */
class Navigator(val route: Route) {

    private val points = route.points
    /** cumulative[i] = başlangıçtan points[i]'ye rota boyunca mesafe (m). */
    private val cumulative = DoubleArray(points.size)
    private val totalLength: Double
    private var lastSegment = 0

    init {
        for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + distance(points[i - 1], points[i])
        totalLength = cumulative.lastOrNull() ?: 0.0
    }

    fun update(location: LatLng): NavProgress {
        // Önce son konumun çevresinde ara (rota aynı yoldan geri dönüyorsa ileriye/geriye
        // sıçramasın); orada uzak kalıyorsa (ör. yeniden rota sonrası) bütün rotada ara.
        var best = Double.MAX_VALUE
        var bestSeg = lastSegment
        var bestT = 0.0
        fun scan(from: Int, untilMeters: Double) {
            for (i in from until points.size - 1) {
                if (cumulative[i] > untilMeters) break
                val (d, t) = projectToSegment(location, points[i], points[i + 1])
                if (d < best) {
                    best = d
                    bestSeg = i
                    bestT = t
                }
            }
        }
        val from = (lastSegment - BACKTRACK_SEGMENTS).coerceAtLeast(0)
        scan(from, cumulative.getOrElse(lastSegment) { 0.0 } + LOOKAHEAD_M)
        if (best > LOST_M) scan(0, Double.MAX_VALUE)
        if (points.size < 2) best = distance(location, points.firstOrNull() ?: location)
        lastSegment = bestSeg

        val a = points.getOrElse(bestSeg) { location }
        val b = points.getOrElse(bestSeg + 1) { a }
        val snapped = LatLng(a.latitude + (b.latitude - a.latitude) * bestT, a.longitude + (b.longitude - a.longitude) * bestT)
        val traveled = (cumulative.getOrElse(bestSeg) { 0.0 } + distance(a, b) * bestT).coerceIn(0.0, totalLength)
        val remaining = totalLength - traveled

        // Geçilmiş manevraları atla (birkaç metre pay bırakarak).
        val upcoming = route.instructions.filter { it.routeOffsetMeters > traveled + PASSED_MARGIN_M }
        val next = upcoming.firstOrNull() ?: route.instructions.lastOrNull()
        val distanceToNext = ((next?.routeOffsetMeters?.toDouble() ?: totalLength) - traveled).coerceAtLeast(0.0)

        val remainingSeconds = if (totalLength > 0) (route.travelTimeSeconds * remaining / totalLength).toInt() else 0
        val arrived = remaining < ARRIVAL_RADIUS_M && best < ARRIVAL_RADIUS_M * 2

        return NavProgress(
            snapped = snapped,
            distanceFromRoute = best,
            traveled = traveled,
            remainingMeters = remaining,
            remainingSeconds = remainingSeconds,
            next = next,
            distanceToNext = distanceToNext,
            afterNext = upcoming.getOrNull(1),
            arrived = arrived,
        )
    }

    /**
     * Bir noktanın (ör. trafik ışığı) rotadaki yeri: rotaya uzaklık, başlangıçtan rota boyunca mesafe
     * ve o noktada gidiş yönü (derece, kuzey=0). İlerleme durumunu değiştirmez.
     */
    fun locate(p: LatLng): RoutePoint? {
        if (points.size < 2) return null
        var best = Double.MAX_VALUE
        var bestSeg = 0
        var bestT = 0.0
        for (i in 0 until points.size - 1) {
            val (d, t) = projectToSegment(p, points[i], points[i + 1])
            if (d < best) {
                best = d
                bestSeg = i
                bestT = t
            }
        }
        val a = points[bestSeg]
        val b = points[bestSeg + 1]
        return RoutePoint(best, cumulative[bestSeg] + distance(a, b) * bestT, bearing(a, b))
    }

    private fun bearing(a: LatLng, b: LatLng): Double {
        val k = cos(Math.toRadians(a.latitude))
        val dx = (b.longitude - a.longitude) * k
        val dy = b.latitude - a.latitude
        return (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360) % 360
    }

    /** Noktanın [a,b] parçasına uzaklığı (m) ve parça üzerindeki oran (0..1). */
    private fun projectToSegment(p: LatLng, a: LatLng, b: LatLng): Pair<Double, Double> {
        // Kısa mesafelerde düzlem varsayımı yeterli: enlem/boylamı metreye çevir.
        val k = cos(Math.toRadians(a.latitude))
        val bx = (b.longitude - a.longitude) * k * M_PER_DEG
        val by = (b.latitude - a.latitude) * M_PER_DEG
        val px = (p.longitude - a.longitude) * k * M_PER_DEG
        val py = (p.latitude - a.latitude) * M_PER_DEG
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return sqrt(dx * dx + dy * dy) to t
    }

    companion object {
        private const val M_PER_DEG = 111_320.0
        private const val BACKTRACK_SEGMENTS = 10
        private const val LOOKAHEAD_M = 2_000.0
        private const val LOST_M = 100.0
        private const val PASSED_MARGIN_M = 5.0
        const val ARRIVAL_RADIUS_M = 30.0

        fun distance(a: LatLng, b: LatLng): Double {
            val k = cos(Math.toRadians((a.latitude + b.latitude) / 2))
            val dx = (b.longitude - a.longitude) * k * M_PER_DEG
            val dy = (b.latitude - a.latitude) * M_PER_DEG
            return sqrt(dx * dx + dy * dy)
        }
    }
}

/** Rotadan sapmayı izler: ~50 m dışarıda birkaç saniye kalınca yeniden rota ister, en fazla 20 sn'de bir. */
class OffRouteDetector(
    private val thresholdMeters: Double = 50.0,
    private val holdMillis: Long = 4_000,
    private val minRerouteIntervalMillis: Long = 20_000,
) {
    private var offSince: Long? = null
    private var lastReroute = 0L

    /** true dönerse yeniden rota istenmeli. accuracy: GPS doğruluğu (m); kötü sinyalde karar verilmez. */
    fun check(distanceFromRoute: Double, accuracy: Float, now: Long): Boolean {
        if (accuracy > thresholdMeters || distanceFromRoute < thresholdMeters) {
            offSince = null
            return false
        }
        val since = offSince ?: now.also { offSince = it }
        if (now - since < holdMillis || now - lastReroute < minRerouteIntervalMillis) return false
        lastReroute = now
        offSince = null
        return true
    }
}

/** TomTom "tagged" talimat metnindeki <street> gibi etiketleri temizler. */
fun Instruction.plainText(): String = message.replace(Regex("<[^>]+>"), "").trim()

/** Manevra kodunu ekranda gösterilecek oka çevirir (araba ekranı için ileride ikon olacak). */
fun maneuverArrow(maneuver: String?): String = when (maneuver) {
    "TURN_RIGHT", "MOTORWAY_EXIT_RIGHT" -> "➡"
    "TURN_LEFT", "MOTORWAY_EXIT_LEFT" -> "⬅"
    "KEEP_RIGHT", "BEAR_RIGHT", "WAYPOINT_RIGHT", "ARRIVE_RIGHT", "TAKE_EXIT", "ENTRANCE_RAMP" -> "⬈"
    "KEEP_LEFT", "BEAR_LEFT", "WAYPOINT_LEFT", "ARRIVE_LEFT" -> "⬉"
    "SHARP_RIGHT" -> "⬊"
    "SHARP_LEFT" -> "⬋"
    "MAKE_UTURN", "TRY_MAKE_UTURN", "ROUNDABOUT_BACK" -> "⮌"
    "ROUNDABOUT_RIGHT", "ROUNDABOUT_LEFT", "ROUNDABOUT_CROSS" -> "⟳"
    "ARRIVE", "WAYPOINT_REACHED" -> "🏁"
    else -> "⬆"
}

/** "350 m", "1,2 km" gibi. Kısa mesafeler 10 m'ye, 1 km altı 50 m'ye yuvarlanır. */
fun formatDistance(meters: Double): String = when {
    meters < 100 -> "${(meters / 10).toInt() * 10} m"
    meters < 1000 -> "${((meters + 25) / 50).toInt() * 50} m"
    meters < 10_000 -> String.format(java.util.Locale("tr", "TR"), "%.1f km", meters / 1000)
    else -> "${(meters / 1000).toInt()} km"
}
