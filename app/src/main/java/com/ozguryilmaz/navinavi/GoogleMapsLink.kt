package com.ozguryilmaz.navinavi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Google Haritalar'dan "Paylaş" ile gelen metinden yeri çıkarır.
 * Google API'si kullanılmaz: kısa bağlantının (maps.app.goo.gl) yönlendirdiği
 * adresteki koordinat okunur. Google bağlantı biçimini değiştirirse burası güncellenmeli.
 */
object GoogleMapsLink {

    // Sadece geçerli URL karakterleri: paylaşım metninde bağlantının arkasına
    // görünmez karakterler (ör. yön işaretleri) eklenebiliyor.
    private val urlRegex = Regex("https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")
    private const val NUM = "(-?\\d{1,3}\\.\\d+)"

    // Öncelik sırasıyla: yerin kendi koordinatı, sorgu parametresi, harita görünümünün ortası.
    private val coordPatterns = listOf(
        Regex("!3d$NUM!4d$NUM"),
        Regex("[?&](?:q|ll|query|destination|daddr|center)=$NUM,\\s*$NUM"),
        Regex("/place/$NUM,\\s*$NUM"),
        Regex("@$NUM,$NUM"),
        Regex("geo:$NUM,$NUM"),
    )

    /** Paylaşılan metinde Google Haritalar bağlantısı yoksa null döner. */
    fun findLink(sharedText: String): String? =
        urlRegex.findAll(sharedText).map { it.value.trimEnd('.', ',', ')') }
            .firstOrNull { it.contains("goo.gl") || it.contains("google.") }

    /** Paylaşım metnindeki ilk satır genelde yerin adıdır. */
    fun nameFrom(sharedText: String): String =
        sharedText.lines().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !urlRegex.containsMatchIn(it) }
            ?: FALLBACK_NAME

    private fun placeNameFrom(url: String): String? =
        Regex("/place/([^/@?]+)").find(url)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it.replace("+", "%20"), "UTF-8") }.getOrNull() }
            ?.takeIf { it.isNotBlank() && parseCoords("/place/$it") == null }

    private const val FALLBACK_NAME = "Google Haritalar'dan paylaşılan yer"

    /**
     * place: koordinat bağlantıda bulunduysa. Bulunamadıysa addressHint, bağlantıdaki
     * yer adı/adres metnidir (adres bulma servisine sorulabilir). log: hata ayrıntısı.
     */
    data class Result(val place: Place?, val addressHint: String?, val log: String)

    suspend fun resolve(sharedText: String): Result = withContext(Dispatchers.IO) {
        val log = StringBuilder()
        val link = findLink(sharedText) ?: return@withContext Result(null, null, "Bağlantı yok")
        val textName = nameFrom(sharedText)

        var url = link
        // Yeni Google Haritalar sürümleri sadece bağlantı paylaşıyor; ad o zaman adresten alınır.
        val name = { if (textName == FALLBACK_NAME) placeNameFrom(url) ?: textName else textName }
        try {
            repeat(MAX_REDIRECTS) {
                log.appendLine("→ ${url.take(120)}")
                parseCoords(url)?.let { return@withContext Result(Place(name(), "", it), null, log.toString()) }
                val conn = open(url)
                try {
                    val code = conn.responseCode
                    val location = conn.getHeaderField("Location")
                    log.appendLine("   HTTP $code")
                    if (code in 300..399 && location != null) {
                        url = unwrapConsent(URL(URL(url), location).toString())
                    } else {
                        // Yönlendirme bitti ve adreste koordinat yok. Google sayfası konumu
                        // JavaScript ile yüklediği için sayfanın içinde de bulunmuyor.
                        log.appendLine("   Bağlantıda koordinat yok")
                        return@withContext Result(null, placeNameFrom(url), log.toString())
                    }
                } finally {
                    conn.disconnect()
                }
            }
            log.appendLine("Çok fazla yönlendirme")
        } catch (e: Exception) {
            log.appendLine("Hata: ${e.javaClass.simpleName}: ${e.message}")
        }
        Result(null, placeNameFrom(url), log.toString())
    }

    fun parseCoords(url: String): LatLng? {
        // Bozuk % dizisi varsa çözmeden dene.
        val decoded = try { URLDecoder.decode(url, "UTF-8") } catch (e: IllegalArgumentException) { url }
        return coordPatterns.firstNotNullOfOrNull { p -> p.find(decoded)?.let { toLatLng(it) } }
    }

    // Çerez onay sayfasına yönlenirse asıl adres "continue" parametresinde durur.
    private fun unwrapConsent(url: String): String {
        if (!url.contains("consent.google.")) return url
        val cont = Regex("[?&]continue=([^&]+)").find(url)?.groupValues?.get(1) ?: return url
        return URLDecoder.decode(cont, "UTF-8")
    }

    private fun toLatLng(m: MatchResult): LatLng? {
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return LatLng(lat, lon)
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9")
    }

    private const val MAX_REDIRECTS = 6
}
