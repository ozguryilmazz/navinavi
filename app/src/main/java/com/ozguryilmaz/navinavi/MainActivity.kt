package com.ozguryilmaz.navinavi

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val updater by lazy { Updater(this) }
    private val apiKeys by lazy { ApiKeys(this) }
    private lateinit var status: TextView
    private lateinit var updateButton: Button
    private lateinit var updateStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        updateButton = findViewById(R.id.update_button)
        updateStatus = findViewById(R.id.update_status)
        updateButton.setOnClickListener { update() }
        findViewById<Button>(R.id.key_button).setOnClickListener { askTomtomKey() }

        showStatus()
        if (!apiKeys.hasTomtom) askTomtomKey()
    }

    private fun showStatus() {
        // Anahtarın kendisini değil, sadece var olup olmadığını gösteriyoruz.
        val keyStatus = if (apiKeys.hasTomtom) "TomTom anahtarı: var ✅" else "TomTom anahtarı: YOK ❌"
        status.text = "Sürüm ${BuildConfig.VERSION_NAME}\n$keyStatus"
    }

    private fun askTomtomKey() {
        val input = EditText(this).apply {
            hint = getString(R.string.key_dialog_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.key_dialog_title)
            .setMessage(R.string.key_dialog_message)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    apiKeys.tomtom = key
                    showStatus()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun update() {
        // İzin yoksa ayar ekranı açılır; kullanıcı izni verip geri dönünce tekrar basar.
        if (!updater.ensureInstallPermission()) {
            updateStatus.text = "\"Bu kaynaktan izin ver\"i açıp geri gelin, sonra tekrar Güncelle'ye basın."
            return
        }
        updateButton.isEnabled = false
        lifecycleScope.launch {
            try {
                updateStatus.text = "Kontrol ediliyor…"
                val release = updater.latestRelease()
                if (release == null) {
                    updateStatus.text = "GitHub'da yayımlanmış sürüm bulunamadı."
                    return@launch
                }
                if (release.buildNumber <= BuildConfig.VERSION_CODE) {
                    updateStatus.text = "Zaten en güncel sürüm (0.1.${BuildConfig.VERSION_CODE})."
                    return@launch
                }
                updateStatus.text = "0.1.${release.buildNumber} indiriliyor…"
                val apk = updater.download(release) { p -> updateStatus.text = "0.1.${release.buildNumber} indiriliyor… %$p" }
                updateStatus.text = "Kurulum ekranı açılıyor…"
                updater.install(apk)
            } catch (e: Exception) {
                updateStatus.text = "Güncelleme başarısız: ${e.message}"
            } finally {
                updateButton.isEnabled = true
            }
        }
    }
}
