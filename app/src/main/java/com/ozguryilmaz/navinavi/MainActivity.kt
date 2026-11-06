package com.ozguryilmaz.navinavi

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Anahtarın kendisini değil, sadece var olup olmadığını gösteriyoruz.
        val keyStatus = if (BuildConfig.TOMTOM_API_KEY.isNotEmpty()) "TomTom anahtarı: var ✅" else "TomTom anahtarı: YOK ❌"
        findViewById<TextView>(R.id.status).text = "Sürüm ${BuildConfig.VERSION_NAME}\n$keyStatus"
    }
}
