package com.ozguryilmaz.navinavi

import java.util.Locale

/**
 * Arama sonuçlarından, yazılan metne isim olarak benzemeyenleri eler ve
 * en benzerleri öne alır. Türkçe harfler (ç, ğ, ı, ö, ş, ü) sadeleştirilir,
 * böylece "kizilay" yazınca "Kızılay" da bulunur.
 */
object PlaceMatcher {

    private val tr = Locale("tr", "TR")

    fun normalize(s: String): String =
        s.lowercase(tr)
            .replace('ç', 'c').replace('ğ', 'g').replace('ı', 'i').replace('ö', 'o')
            .replace('ş', 's').replace('ü', 'u').replace('â', 'a').replace('î', 'i').replace('û', 'u')
            .replace("i̇", "i") // büyük İ'nin küçültülmüş hali

    private fun words(s: String) = normalize(s).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Eşleşmeyenler listeden çıkar; kalanlar benzerliğe, eşitse TomTom'un sırasına göre dizilir. */
    fun filter(query: String, places: List<Place>, max: Int = 7): List<Place> {
        val tokens = words(query)
        if (tokens.isEmpty()) return emptyList()
        val q = normalize(query.trim())
        return places
            .mapIndexedNotNull { index, p -> score(q, tokens, p)?.let { Triple(it, index, p) } }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }
            .distinctBy { it.name to it.address }
            .take(max)
    }

    /** Küçük puan = daha iyi. null = eşleşmiyor. */
    private fun score(q: String, tokens: List<String>, p: Place): Int? {
        val name = normalize(p.name)
        val nameWords = words(p.name)
        val allWords = nameWords + words(p.address)
        // Her yazılan kelime, bir kelimenin başına uymalı (son kelime yarım olabilir).
        if (!tokens.all { t -> allWords.any { it.startsWith(t) } }) return null
        return when {
            name.startsWith(q) -> 0
            tokens.all { t -> nameWords.any { it.startsWith(t) } } -> 1
            else -> 2 // bazı kelimeler sadece adreste geçiyor
        }
    }
}
