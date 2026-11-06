package com.ozguryilmaz.navinavi

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

class MainActivity : AppCompatActivity() {

    private val updater by lazy { Updater(this) }
    private val apiKeys by lazy { ApiKeys(this) }
    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var updateButton: Button
    private lateinit var updateStatus: TextView
    private var map: MapLibreMap? = null
    private var style: Style? = null

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) enableLocation()
        else Toast.makeText(this, R.string.location_denied, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // MapLibre, MapView oluşturulmadan önce başlatılmalı.
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.map)
        status = findViewById(R.id.status)
        updateButton = findViewById(R.id.update_button)
        updateStatus = findViewById(R.id.update_status)
        updateButton.setOnClickListener { update() }
        findViewById<Button>(R.id.key_button).setOnClickListener { askTomtomKey() }
        findViewById<View>(R.id.my_location).setOnClickListener { followMe() }
        applySystemBarInsets()

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            // Logo/telif yazısı boşluklarının harita hazır olunca da uygulanması için.
            ViewCompat.requestApplyInsets(findViewById(R.id.root))
            // Ekran yeniden oluşturulduysa MapView eski kamerayı kendisi geri yükler;
            // ilk açılışta konum gelene kadar Türkiye'nin ortası.
            val firstStart = savedInstanceState == null
            if (firstStart) {
                m.cameraPosition = CameraPosition.Builder().target(LatLng(39.0, 35.0)).zoom(5.0).build()
            }
            m.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { s ->
                style = s
                if (hasLocationPermission()) enableLocation(follow = firstStart) else requestLocationPermission()
            }
        }

        showStatus()
        if (!apiKeys.hasTomtom) askTomtomKey()
    }

    // Android 15+ uygulamayı tam ekran çizer; paneller durum/gezinme çubuğunun altına girmesin.
    private fun applySystemBarInsets() {
        val topPanel = findViewById<View>(R.id.top_panel)
        val fab = findViewById<View>(R.id.my_location)
        val margin = (12 * resources.displayMetrics.density).toInt()
        val fabMargin = (20 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topPanel.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = bars.top + margin }
            fab.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bars.bottom + fabMargin }
            map?.uiSettings?.let {
                it.setAttributionMargins(it.attributionMarginLeft, 0, 0, bars.bottom + margin)
                it.setLogoMargins(it.logoMarginLeft, 0, 0, bars.bottom + margin)
            }
            insets
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        locationPermission.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation(follow: Boolean = true) {
        val m = map ?: return
        val s = style ?: return
        val lc = m.locationComponent
        if (!lc.isLocationComponentActivated) {
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, s)
                    .useDefaultLocationEngine(true)
                    .build()
            )
        }
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.GPS
        if (follow) followMe()
    }

    /** Kamerayı konuma kilitler; kullanıcı haritayı kaydırınca kilit kendiliğinden açılır. */
    @SuppressLint("MissingPermission")
    private fun followMe() {
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }
        val lc = map?.locationComponent ?: return
        if (!lc.isLocationComponentActivated) {
            enableLocation()
            return
        }
        lc.cameraMode = CameraMode.TRACKING_GPS
        // Kullanıcı zaten yakınlaştırdıysa yakınlığına dokunma.
        if ((map?.cameraPosition?.zoom ?: 0.0) < 14.0) lc.zoomWhileTracking(16.0)
    }

    private fun showStatus() {
        // Anahtarın kendisini değil, sadece var olup olmadığını gösteriyoruz.
        val keyStatus = if (apiKeys.hasTomtom) "TomTom anahtarı: var ✅" else "TomTom anahtarı: YOK ❌"
        status.text = "NaviNavi ${BuildConfig.VERSION_NAME} · $keyStatus"
    }

    private fun showUpdateStatus(text: String) {
        updateStatus.visibility = View.VISIBLE
        updateStatus.text = text
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
            showUpdateStatus("\"Bu kaynaktan izin ver\"i açıp geri gelin, sonra tekrar Güncelle'ye basın.")
            return
        }
        updateButton.isEnabled = false
        lifecycleScope.launch {
            try {
                showUpdateStatus("Kontrol ediliyor…")
                val release = updater.latestRelease()
                if (release == null) {
                    showUpdateStatus("GitHub'da yayımlanmış sürüm bulunamadı.")
                    return@launch
                }
                if (release.buildNumber <= BuildConfig.VERSION_CODE) {
                    showUpdateStatus("Zaten en güncel sürüm (0.1.${BuildConfig.VERSION_CODE}).")
                    return@launch
                }
                showUpdateStatus("0.1.${release.buildNumber} indiriliyor…")
                val apk = updater.download(release) { p -> showUpdateStatus("0.1.${release.buildNumber} indiriliyor… %$p") }
                showUpdateStatus("Kurulum ekranı açılıyor…")
                updater.install(apk)
            } catch (e: Exception) {
                showUpdateStatus("Güncelleme başarısız: ${e.message}")
            } finally {
                updateButton.isEnabled = true
            }
        }
    }

    // MapView yaşam döngüsünü kendisi takip etmez; her olayı ona iletmek gerekir.
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    private companion object {
        // Ücretsiz, anahtarsız harita stili (TomTom kotası harcanmaz).
        const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    }
}
