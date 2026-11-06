package com.ozguryilmaz.navinavi

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng

/** Kullanıcının kaydettiği yerler (Ev, İş…). Telefonda saklanır, kota harcamaz. */
class Favorites(context: Context) {

    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun all(): List<Place> {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Place(o.getString("name"), FAVORITE_MARK, LatLng(o.getDouble("lat"), o.getDouble("lon")))
        }
    }

    /** Aynı isimde kayıt varsa üzerine yazar. */
    fun save(name: String, position: LatLng) {
        val list = all().filterNot { it.name.equals(name, ignoreCase = true) } +
            Place(name, FAVORITE_MARK, position)
        write(list)
    }

    fun remove(name: String) = write(all().filterNot { it.name == name })

    private fun write(list: List<Place>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("lat", it.position.latitude).put("lon", it.position.longitude))
        }
        prefs.edit { putString(KEY, arr.toString()) }
    }

    companion object {
        private const val KEY = "list"
        /** Öneri listesinde favoriyi ayırt etmek için adres satırına yazılır. */
        const val FAVORITE_MARK = "★ Favori"
    }
}
