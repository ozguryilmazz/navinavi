package com.ozguryilmaz.navinavi

import android.content.Context
import androidx.core.content.edit

/**
 * TomTom anahtarı APK'ya gömülmez (release'ler herkese açık).
 * Kullanıcı uygulamada bir kez girer, telefonda saklanır.
 */
class ApiKeys(context: Context) {

    private val prefs = context.getSharedPreferences("api_keys", Context.MODE_PRIVATE)

    var tomtom: String
        get() = prefs.getString(KEY_TOMTOM, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_TOMTOM, value.trim()) }

    val hasTomtom: Boolean get() = tomtom.isNotEmpty()

    private companion object {
        const val KEY_TOMTOM = "tomtom"
    }
}
