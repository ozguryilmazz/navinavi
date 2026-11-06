package com.ozguryilmaz.navinavi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
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
    private val tomtom by lazy { TomTomApi(apiKeys) }
    private lateinit var searchBox: EditText
    private lateinit var suggestions: LinearLayout
    private var searchJob: Job? = null
    private var suppressSearch = false
    // Aynı metni tekrar tekrar sormamak için (kota tasarrufu).
    private val searchCache = HashMap<String, List<Place>>()
    private var lastResults: List<Place> = emptyList()
    private lateinit var routeRow: View
    private lateinit var routeInfo: TextView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var routeLayer: RouteLayer? = null
    private var route: Route? = null
    private var destination: Place? = null
    private val favorites by lazy { Favorites(this) }

    // Navigasyon durumu
    private var navigator: Navigator? = null
    private val offRoute = OffRouteDetector()
    private var navigating = false
    private var rerouting = false
    private var movedOnce = false
    private lateinit var startNavButton: Button
    // Android 10 ve öncesinde bu metotların hepsi zorunlu; lambda yazılırsa çöker.
    private val navLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onNavLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Android 10 ve öncesi için")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

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
        findViewById<View>(R.id.my_location).apply {
            setOnClickListener { followMe() }
            setOnLongClickListener { saveCurrentLocation(); true }
        }
        searchBox = findViewById(R.id.search)
        suggestions = findViewById(R.id.suggestions)
        // Klavyedeki ara tuşu sadece klavyeyi kapatır; sonuçlar zaten yazarken listeleniyor.
        searchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else false
        }
        searchBox.doAfterTextChanged { text ->
            if (!suppressSearch) onSearchTextChanged(text?.toString().orEmpty())
        }
        // Boş kutuya dokununca favoriler listelenir.
        searchBox.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && searchBox.text.isBlank()) showSuggestions(emptyList(), "")
        }
        routeRow = findViewById(R.id.route_row)
        routeInfo = findViewById(R.id.route_info)
        findViewById<Button>(R.id.clear_route).setOnClickListener { clearRoute() }
        startNavButton = findViewById(R.id.start_nav)
        startNavButton.setOnClickListener { startNavigation() }
        findViewById<Button>(R.id.stop_nav).setOnClickListener { stopNavigation() }
        findViewById<Button>(R.id.save_favorite).setOnClickListener { destination?.let { askFavoriteName(it) } }
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
            m.addOnMapLongClickListener { p ->
                routeTo(Place(getString(R.string.long_press_point), "", p))
                true
            }
            m.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { s ->
                style = s
                routeLayer = RouteLayer(s)
                if (hasLocationPermission()) enableLocation(follow = firstStart) else requestLocationPermission()
            }
        }

        showStatus()
        if (!apiKeys.hasTomtom) askTomtomKey()
        if (savedInstanceState == null) handleShare(intent)
    }

    // Uygulama zaten açıkken gelen paylaşımlar (launchMode=singleTask).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        // Bazı uygulamalar metni String değil CharSequence olarak gönderir.
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return
        if (GoogleMapsLink.findLink(text) == null) {
            showError(TomTomException(getString(R.string.share_no_link)))
            return
        }
        Toast.makeText(this, R.string.share_resolving, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = GoogleMapsLink.resolve(text)
            val place = result.place
            if (place == null) {
                // Adresten bulunan konum yanlış yere rota çizdirmesin: sadece bölgeyi göster,
                // kullanıcı tam yeri basılı tutarak seçsin.
                val approx = result.addressHint?.let { geocodeApprox(it) }
                if (approx != null) {
                    showApproxArea(approx.position)
                    return@launch
                }
                // Sorunu bulabilmek için ayrıntıyı da göster.
                val detail = "Paylaşılan metin:\n${text.take(300)}\n\n${result.log.take(1200)}"
                showError(TomTomException(getString(R.string.share_no_coords) + "\n\n" + detail))
                return@launch
            }
            suppressSearch = true
            searchBox.setText(place.name)
            suppressSearch = false
            hideSuggestions()
            // Uygulama yeni açıldıysa konum birkaç saniye sonra gelir; o zamana kadar bekle.
            var waited = 0L
            while (currentLocation() == null && waited < LOCATION_WAIT_MS) {
                delay(500)
                waited += 500
            }
            routeTo(place)
        }
    }

    // Android 15+ uygulamayı tam ekran çizer; paneller durum/gezinme çubuğunun altına girmesin.
    private fun applySystemBarInsets() {
        val topPanel = findViewById<View>(R.id.top_panel)
        val fab = findViewById<View>(R.id.my_location)
        val navPanel = findViewById<View>(R.id.nav_panel)
        val navBottom = findViewById<View>(R.id.nav_bottom)
        val margin = (12 * resources.displayMetrics.density).toInt()
        val fabMargin = (20 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topPanel.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = bars.top + margin }
            navPanel.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = bars.top + margin }
            fab.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bars.bottom + fabMargin }
            navBottom.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bars.bottom + fabMargin }
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
        // Navigasyon dışında ok pusulayı izler: telefonu çevirince döner.
        lc.renderMode = RenderMode.COMPASS
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
        lc.cameraMode = when {
            !navigating -> CameraMode.TRACKING // kuzey yukarıda, sadece konumu izle
            movedOnce -> CameraMode.TRACKING_GPS
            else -> CameraMode.TRACKING_COMPASS
        }
        // Kullanıcı zaten yakınlaştırdıysa yakınlığına dokunma.
        if ((map?.cameraPosition?.zoom ?: 0.0) < 14.0) lc.zoomWhileTracking(16.0)
    }

    private fun currentLocation(): LatLng? {
        val lc = map?.locationComponent ?: return null
        if (!lc.isLocationComponentActivated) return null
        val l = lc.lastKnownLocation ?: return null
        return LatLng(l.latitude, l.longitude)
    }

    /**
     * Google bağlantısında koordinat yoksa, içindeki adresi TomTom'a sorar.
     * Bulamazsa baştaki parçayı atıp tekrar dener ("Ipay, Özbek, Urla/İzmir" → "Özbek, Urla/İzmir").
     */
    private suspend fun geocodeApprox(address: String): Place? {
        var parts = address.replace("/", ", ").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        repeat(3) {
            if (parts.size < 2) return null
            val found = try {
                tomtom.geocode(parts.joinToString(", "), near = currentLocation())
            } catch (e: Exception) {
                return null
            }
            if (found != null) return found
            parts = parts.drop(1)
        }
        return null
    }

    private fun showApproxArea(p: LatLng) {
        val m = map ?: return
        if (m.locationComponent.isLocationComponentActivated) m.locationComponent.cameraMode = CameraMode.NONE
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(p, 14.0), 800)
        AlertDialog.Builder(this).setMessage(R.string.share_approx).setPositiveButton(android.R.string.ok, null).show()
    }

    /** Yeşil konum butonuna uzun basınca: şu an bulunduğum yeri favoriye kaydet (ör. işe varınca "İş"). */
    private fun saveCurrentLocation() {
        val here = currentLocation()
        if (here == null) {
            Toast.makeText(this, R.string.no_location_yet, Toast.LENGTH_LONG).show()
            return
        }
        askFavoriteName(Place(getString(R.string.long_press_point), "", here), R.string.favorite_here_title)
    }

    private fun askFavoriteName(place: Place, title: Int = R.string.favorite_dialog_title) {
        val input = EditText(this).apply {
            hint = getString(R.string.favorite_dialog_hint)
            isSingleLine = true
            // Haritadan seçilen noktanın genel adı yerine boş başlasın.
            if (place.name != getString(R.string.long_press_point)) setText(place.name)
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    favorites.save(name, place.position)
                    if (destination?.position == place.position) destination = place.copy(name = name)
                    Toast.makeText(this, R.string.favorite_saved, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteFavorite(p: Place) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.favorite_delete, p.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                favorites.remove(p.name)
                val query = searchBox.text.toString().trim()
                showSuggestions(PlaceMatcher.filter(query, lastResults), query)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchBox.windowToken, 0)
        searchBox.clearFocus()
    }

    /**
     * Yazdıkça: önce eldeki sonuçlar anında süzülür, yazma durunca (400 ms) TomTom'a sorulur.
     * 3 harften kısa metinler için istek atılmaz.
     */
    private fun onSearchTextChanged(text: String) {
        searchJob?.cancel()
        val query = text.trim()
        if (query.length < MIN_SEARCH_CHARS) {
            showSuggestions(emptyList(), query)
            return
        }
        showSuggestions(PlaceMatcher.filter(query, lastResults), query)

        val key = PlaceMatcher.normalize(query)
        searchCache[key]?.let { cached ->
            lastResults = cached
            showSuggestions(PlaceMatcher.filter(query, cached), query)
            return
        }
        searchJob = lifecycleScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            try {
                val places = tomtom.search(query, near = currentLocation())
                searchCache[key] = places
                lastResults = places
                showSuggestions(PlaceMatcher.filter(query, places), query, searched = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Her harfte pencere açılmasın diye hata listenin içinde gösterilir.
                val msg = if (e is TomTomException) e.message else "Arama hatası: ${e.message}"
                showSuggestions(emptyList(), query, searched = true, message = msg)
            }
        }
    }

    /**
     * Öneri listesi: önce eşleşen favoriler (kutu boşsa hepsi), sonra TomTom sonuçları.
     * Favoriler 1 harften itibaren, TomTom sonuçları 3 harften itibaren gösterilir.
     */
    private fun showSuggestions(places: List<Place>, query: String, searched: Boolean = false, message: String? = null) {
        suggestions.removeAllViews()
        val favs = if (query.isEmpty()) favorites.all() else PlaceMatcher.filter(query, favorites.all(), max = 3)
        val api = if (query.length >= MIN_SEARCH_CHARS) places.filterNot { p -> favs.any { it.name == p.name } } else emptyList()
        val showMessage = searched && api.isEmpty() && query.length >= MIN_SEARCH_CHARS
        if (favs.isEmpty() && api.isEmpty() && !showMessage) {
            suggestions.visibility = View.GONE
            return
        }
        suggestions.visibility = View.VISIBLE
        val pad = (8 * resources.displayMetrics.density).toInt()
        for (p in favs + api) {
            suggestions.addView(TextView(this).apply {
                text = if (p.address.isEmpty()) p.name else "${p.name}\n${p.address}"
                setTextColor(0xFF212121.toInt())
                textSize = 15f
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { pickPlace(p) }
                // Favoriye uzun basınca silme sorulur.
                if (p.address == Favorites.FAVORITE_MARK) {
                    setOnLongClickListener { confirmDeleteFavorite(p); true }
                }
            })
        }
        if (showMessage && favs.isEmpty()) {
            suggestions.addView(TextView(this).apply {
                text = message ?: getString(R.string.no_results)
                setTextColor(0xFF757575.toInt())
                setPadding(pad, pad, pad, pad)
            })
        }
    }

    private fun hideSuggestions() {
        suggestions.removeAllViews()
        suggestions.visibility = View.GONE
    }

    private fun pickPlace(p: Place) {
        searchJob?.cancel()
        suppressSearch = true
        searchBox.setText(p.name)
        suppressSearch = false
        hideSuggestions()
        hideKeyboard()
        routeTo(p)
    }

    private fun routeTo(place: Place) {
        val from = currentLocation()
        if (from == null) {
            Toast.makeText(this, R.string.no_location_yet, Toast.LENGTH_LONG).show()
            return
        }
        destination = place
        route = null
        startNavButton.isEnabled = false
        hideSuggestions()
        routeLayer?.showDestination(place.position)
        routeRow.visibility = View.VISIBLE
        routeInfo.text = "Rota hesaplanıyor…"
        lifecycleScope.launch {
            try {
                val r = tomtom.route(from, place.position)
                route = r
                routeLayer?.showRoute(r.points)
                routeInfo.text = "${place.name}\n${formatSummary(r)}"
                startNavButton.isEnabled = true
                fitRoute(r)
            } catch (e: Exception) {
                routeInfo.text = place.name
                showError(e)
            }
        }
    }

    private fun fitRoute(r: Route) {
        val m = map ?: return
        if (r.points.size < 2) return
        // Kamera konum takibindeyse rotayı göstermek için takibi bırak.
        if (m.locationComponent.isLocationComponentActivated) m.locationComponent.cameraMode = CameraMode.NONE
        val bounds = LatLngBounds.Builder().includes(r.points).build()
        val pad = (48 * resources.displayMetrics.density).toInt()
        val topPanel = findViewById<View>(R.id.top_panel)
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad, topPanel.bottom + pad, pad, pad * 2), 800)
    }

    // ---- Adım adım navigasyon ----

    @SuppressLint("MissingPermission")
    private fun startNavigation() {
        val r = route ?: return
        val lc = map?.locationComponent ?: return
        if (!lc.isLocationComponentActivated) return
        navigator = Navigator(r)
        navigating = true
        rerouting = false

        findViewById<View>(R.id.top_panel).visibility = View.GONE
        findViewById<View>(R.id.nav_panel).visibility = View.VISIBLE
        findViewById<View>(R.id.nav_bottom).visibility = View.VISIBLE
        findViewById<TextView>(R.id.nav_text).setText(R.string.gps_waiting)
        // Navigasyonda ekran kapanmasın.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Kamera arabanın arkasından, gidiş yönüne dönük ve eğik baksın; araç ekranın alt tarafında dursun.
        // GPS yönü ancak hareket ederken bilinir; araç ilk kez hızlanana kadar pusula kullanılır.
        movedOnce = false
        lc.renderMode = RenderMode.COMPASS
        lc.cameraMode = CameraMode.TRACKING_COMPASS
        lc.zoomWhileTracking(17.0)
        lc.tiltWhileTracking(45.0)
        lc.paddingWhileTracking(doubleArrayOf(0.0, mapView.height * 0.4, 0.0, 0.0))

        val lm = getSystemService(LocationManager::class.java)
        val provider = if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        try {
            lm.requestLocationUpdates(provider, 1000L, 0f, navLocationListener, mainLooper)
        } catch (e: Exception) {
            Toast.makeText(this, "Konum servisi açılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
        lc.lastKnownLocation?.let { onNavLocation(it) }
    }

    private fun stopNavigation(arrived: Boolean = false) {
        if (!navigating) return
        navigating = false
        navigator = null
        getSystemService(LocationManager::class.java).removeUpdates(navLocationListener)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        findViewById<View>(R.id.nav_panel).visibility = View.GONE
        findViewById<View>(R.id.nav_bottom).visibility = View.GONE
        findViewById<View>(R.id.top_panel).visibility = View.VISIBLE

        map?.locationComponent?.let { lc ->
            if (lc.isLocationComponentActivated) {
                lc.paddingWhileTracking(doubleArrayOf(0.0, 0.0, 0.0, 0.0))
                lc.tiltWhileTracking(0.0)
                lc.renderMode = RenderMode.COMPASS
            }
        }
        if (arrived) {
            clearRoute()
            AlertDialog.Builder(this).setMessage(R.string.arrived).setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private fun onNavLocation(location: Location) {
        val nav = navigator ?: return
        if (!navigating || rerouting) return
        // İlk gerçek harekette GPS yönüne geç: arabada pusula titrer, GPS yönü kırmızı ışıkta da sabit kalır.
        if (!movedOnce && location.hasBearing() && location.hasSpeed() && location.speed > MOVING_SPEED_MPS) {
            movedOnce = true
            map?.locationComponent?.let {
                it.renderMode = RenderMode.GPS
                it.cameraMode = CameraMode.TRACKING_GPS
            }
        }
        val p = nav.update(LatLng(location.latitude, location.longitude))

        if (p.arrived) {
            stopNavigation(arrived = true)
            return
        }
        if (offRoute.check(p.distanceFromRoute, location.accuracy, SystemClock.elapsedRealtime())) {
            reroute(LatLng(location.latitude, location.longitude))
            return
        }
        showGuidance(p)
    }

    private fun showGuidance(p: NavProgress) {
        val next = p.next
        findViewById<TextView>(R.id.nav_arrow).text = maneuverArrow(next?.maneuver)
        findViewById<TextView>(R.id.nav_distance).text = formatDistance(p.distanceToNext)
        findViewById<TextView>(R.id.nav_text).text = next?.plainText().orEmpty()

        // Sıradaki iki manevra birbirine yakınsa ikincisini de önceden göster.
        val then = findViewById<TextView>(R.id.nav_then)
        val after = p.afterNext
        if (next != null && after != null && after.routeOffsetMeters - next.routeOffsetMeters < 200) {
            then.text = "Ardından ${maneuverArrow(after.maneuver)} ${after.plainText()}"
            then.visibility = View.VISIBLE
        } else {
            then.visibility = View.GONE
        }

        val eta = java.time.LocalTime.now().plusSeconds(p.remainingSeconds.toLong())
        findViewById<TextView>(R.id.nav_summary).text =
            "${formatDistance(p.remainingMeters)} · ${formatDuration(p.remainingSeconds)} · " +
                String.format(Locale("tr", "TR"), "%02d:%02d", eta.hour, eta.minute)
    }

    /** Rotadan çıkıldı: bulunduğum yerden hedefe yeni rota (OffRouteDetector en fazla 20 sn'de bir izin verir). */
    private fun reroute(from: LatLng) {
        val dest = destination ?: return
        rerouting = true
        findViewById<TextView>(R.id.nav_arrow).text = "⟲"
        findViewById<TextView>(R.id.nav_distance).text = ""
        findViewById<TextView>(R.id.nav_text).setText(R.string.rerouting)
        findViewById<View>(R.id.nav_then).visibility = View.GONE
        lifecycleScope.launch {
            try {
                val r = tomtom.route(from, dest.position)
                if (!navigating) return@launch
                route = r
                navigator = Navigator(r)
                routeLayer?.showRoute(r.points)
            } catch (e: Exception) {
                // Navigasyonu kesme; eski rotayla devam, bir sonraki sapmada tekrar denenir.
                val msg = if (e is TomTomException) e.message else e.message
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            } finally {
                rerouting = false
            }
        }
    }

    private fun clearRoute() {
        stopNavigation()
        startNavButton.isEnabled = false
        route = null
        routeLayer?.clear()
        destination = null
        routeRow.visibility = View.GONE
        suppressSearch = true
        searchBox.text.clear()
        suppressSearch = false
        hideSuggestions()
    }

    private fun formatSummary(r: Route): String {
        val km = if (r.lengthMeters >= 10_000) "${r.lengthMeters / 1000} km"
        else String.format(Locale("tr", "TR"), "%.1f km", r.lengthMeters / 1000.0)
        val delayMin = r.trafficDelaySeconds / 60
        val traffic = if (delayMin > 0) " (trafik +$delayMin dk)" else ""
        return "$km · ${formatDuration(r.travelTimeSeconds)}$traffic"
    }

    private fun formatDuration(seconds: Int): String {
        val min = (seconds + 30) / 60
        return if (min < 60) "$min dk" else "${min / 60} sa ${min % 60} dk"
    }

    private fun showError(e: Exception) {
        val msg = if (e is TomTomException) e.message else "Beklenmeyen hata: ${e.message}"
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
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
    override fun onDestroy() {
        getSystemService(LocationManager::class.java).removeUpdates(navLocationListener)
        mapView.onDestroy()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    private companion object {
        // Ücretsiz, anahtarsız harita stili (TomTom kotası harcanmaz).
        const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val MIN_SEARCH_CHARS = 3
        const val SEARCH_DEBOUNCE_MS = 400L
        const val LOCATION_WAIT_MS = 20_000L
        const val MOVING_SPEED_MPS = 2.5f // ~9 km/sa
    }
}
