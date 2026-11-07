package com.ozguryilmaz.navinavi

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

/**
 * Navigasyonun tek merkezi: hedef, rota, ilerleme. Telefon ekranı (MainActivity) ve
 * araba ekranı (Android Auto) aynı durumu izler; hangisinden başlatılırsa öbürü de görür.
 * Konum güncellemeleri NavigationService'ten gelir, böylece uygulama arka plandayken de sürer.
 * Bütün çağrılar ana iş parçacığından yapılmalı.
 */
object NavSession {

    data class State(
        val destination: Place? = null,
        val route: Route? = null,
        val calculating: Boolean = false,
        val navigating: Boolean = false,
        val rerouting: Boolean = false,
        val progress: NavProgress? = null,
        val location: Location? = null,
        /** Rota üzerindeki trafik ışıkları (OSM), rota başından uzaklığa göre sıralı. */
        val lights: List<RouteLight> = emptyList(),
        /** 200 m içindeki sıradaki ışık; yoksa null. */
        val nextLight: RouteLight? = null,
        val nextLightDistance: Double = 0.0,
    )

    sealed interface Event {
        data object Arrived : Event
        data class Error(val message: String) : Event
        /** Kısa bilgi (ör. "Yeşil kaydedildi"). */
        data class Info(val message: String) : Event
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private lateinit var app: Context
    private val api by lazy { TomTomApi(ApiKeys(app)) }
    private val scope = MainScope()
    private var navigator: Navigator? = null
    private var offRoute = OffRouteDetector()
    private var routeRequest = 0
    private val store by lazy { LightStore(app) }
    private var lightsJob: Job? = null
    // Dur-kalktan otomatik kayıt için: hangi ışıkta, ne zamandan beri duruyoruz.
    private var stopLight: RouteLight? = null
    private var stoppedSince = 0L
    /** Işık anahtarı → o ışığa en son kayıt (kullanıcı ya da otomatik) yapılan an (elapsedRealtime). */
    private val lastRecordAt = HashMap<String, Long>()

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    /** Hedefe rota hesaplar (önizleme, navigasyon başlamaz). Bu arada başka hedef seçildiyse null döner. */
    suspend fun routeTo(from: LatLng, place: Place): Route? {
        stop()
        val request = ++routeRequest
        _state.update { State(destination = place, calculating = true, location = it.location) }
        try {
            val r = api.route(from, place.position)
            if (request != routeRequest) return null
            _state.update { it.copy(route = r, calculating = false) }
            loadLights(r)
            return r
        } catch (e: Exception) {
            if (request == routeRequest) _state.update { it.copy(calculating = false) }
            throw e
        }
    }

    /** Araba ekranı için: rota hesapla ve hemen navigasyonu başlat. Hata olursa Event.Error yayınlanır. */
    fun routeAndStart(context: Context, from: LatLng, place: Place) {
        scope.launch {
            try {
                if (routeTo(from, place) != null) start(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(Event.Error(errorText(e)))
            }
        }
    }

    fun start(context: Context) {
        val r = _state.value.route ?: return
        if (_state.value.navigating) return
        navigator = Navigator(r)
        offRoute = OffRouteDetector()
        _state.update { it.copy(navigating = true, rerouting = false, progress = null) }
        try {
            ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java))
        } catch (e: Exception) {
            _events.tryEmit(Event.Error("Navigasyon servisi başlatılamadı: ${e.message}"))
        }
        lastKnownLocation()?.let { onLocation(it) }
    }

    fun stop() {
        if (!_state.value.navigating) return
        navigator = null
        stopLight = null
        _state.update { it.copy(navigating = false, rerouting = false, progress = null, nextLight = null) }
        app.stopService(Intent(app, NavigationService::class.java))
    }

    /** Hedefi ve rotayı tamamen siler. */
    fun clear() {
        stop()
        routeRequest++
        lightsJob?.cancel()
        _state.update { State(location = it.location) }
    }

    /** Hedef favoriye kaydedilince adı güncellensin. */
    fun renameDestination(place: Place) {
        _state.update { if (it.destination?.position == place.position) it.copy(destination = place) else it }
    }

    /** NavigationService her GPS konumunda çağırır. */
    fun onLocation(location: Location) {
        _state.update { it.copy(location = location) }
        val nav = navigator ?: return
        val s = _state.value
        if (!s.navigating || s.rerouting) return
        val here = LatLng(location.latitude, location.longitude)
        val p = nav.update(here)
        if (p.arrived) {
            clear()
            _events.tryEmit(Event.Arrived)
            return
        }
        if (offRoute.check(p.distanceFromRoute, location.accuracy, SystemClock.elapsedRealtime())) {
            reroute(here)
            return
        }
        val light = s.lights.firstOrNull { it.routeOffset > p.traveled - LIGHT_PASSED_M }
        val lightDist = light?.let { it.routeOffset - p.traveled } ?: 0.0
        val shown = light?.takeIf { lightDist <= LIGHT_SHOW_M }
        _state.update { it.copy(progress = p, nextLight = shown, nextLightDistance = lightDist.coerceAtLeast(0.0)) }
        autoRecord(location, p.traveled, s.lights)
    }

    // ---- Trafik ışıkları ----

    /** Rota üzerindeki ışıkları arka planda çeker; başarısız olursa navigasyon ışıksız sürer. */
    private fun loadLights(r: Route) {
        lightsJob?.cancel()
        _state.update { it.copy(lights = emptyList(), nextLight = null) }
        lightsJob = scope.launch {
            try {
                val lights = TrafficLights.along(r)
                if (_state.value.route === r) _state.update { it.copy(lights = lights) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Trafik ışıkları alınamadı", e)
            }
        }
    }

    /**
     * 🟢/🔴 butonu: en yakın ışığa (önümüzdeki 250 m ya da az önce geçilen 40 m) basıldığı anı kaydeder.
     * Tek dokunuş, onay yok; sonuç kısa bir bilgiyle söylenir.
     */
    fun recordLight(kind: LightRecord.Kind) {
        val s = _state.value
        val p = s.progress
        if (!s.navigating || p == null) {
            _events.tryEmit(Event.Info("Işık kaydı için önce navigasyonu başlatın"))
            return
        }
        val light = s.lights
            .filter { it.routeOffset - p.traveled in -40.0..250.0 }
            .minByOrNull { kotlin.math.abs(it.routeOffset - p.traveled) }
        if (light == null) {
            _events.tryEmit(Event.Info("Yakında bilinen trafik ışığı yok"))
            return
        }
        save(light, kind, gpsNow(), LightRecord.Source.USER)
        _events.tryEmit(Event.Info(if (kind == LightRecord.Kind.GREEN) "🟢 Yeşil kaydedildi" else "🔴 Kırmızı kaydedildi"))
    }

    /**
     * Butona basılmazsa: ışığın dibinde (30 m) en az 5 sn durup kalkınca yeşilin yandığı
     * yaklaşık olarak kaydedilir (kalkış anı − 3 sn tepki payı). Güveni düşük, "AUTO" işaretli.
     */
    private fun autoRecord(location: Location, traveled: Double, lights: List<RouteLight>) {
        if (!location.hasSpeed()) return
        val now = SystemClock.elapsedRealtime()
        if (location.speed < STOP_MPS) {
            if (stopLight == null) {
                stopLight = lights.firstOrNull { it.routeOffset - traveled in -5.0..AUTO_NEAR_M }
                stoppedSince = now
            }
        } else if (location.speed > GO_MPS) {
            val l = stopLight
            stopLight = null
            if (l == null || now - stoppedSince < AUTO_MIN_STOP_MS) return
            if (l.routeOffset - traveled < -30) return
            // Kullanıcı bu ışığa az önce bastıysa onunki yeter.
            if (now - (lastRecordAt[l.key] ?: 0L) < 60_000) return
            save(l, LightRecord.Kind.GREEN, gpsNow() - 3_000, LightRecord.Source.AUTO)
        }
    }

    private fun save(light: RouteLight, kind: LightRecord.Kind, timeMs: Long, source: LightRecord.Source) {
        lastRecordAt[light.key] = SystemClock.elapsedRealtime()
        scope.launch(Dispatchers.IO) {
            try {
                store.add(light, kind, timeMs, source)
            } catch (e: Exception) {
                Log.e(TAG, "Işık kaydı yazılamadı", e)
            }
        }
    }

    /** Şimdiki an, GPS saatine göre (telefon saati yanlış olsa da saniyesi doğru olsun). */
    private fun gpsNow(): Long {
        val l = _state.value.location ?: return System.currentTimeMillis()
        if (l.time <= 0) return System.currentTimeMillis()
        return l.time + (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
    }

    /** Rotadan çıkıldı: bulunduğum yerden hedefe yeni rota (OffRouteDetector en fazla 20 sn'de bir izin verir). */
    private fun reroute(from: LatLng) {
        val dest = _state.value.destination ?: return
        _state.update { it.copy(rerouting = true) }
        scope.launch {
            try {
                val r = api.route(from, dest.position)
                if (!_state.value.navigating) return@launch
                navigator = Navigator(r)
                _state.update { it.copy(route = r) }
                loadLights(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Navigasyonu kesme; eski rotayla devam, bir sonraki sapmada tekrar denenir.
                _events.tryEmit(Event.Error(errorText(e)))
            } finally {
                _state.update { it.copy(rerouting = false) }
            }
        }
    }

    /** Son bilinen konum: önce servisten gelen, yoksa sistemin hatırladığı. */
    @SuppressLint("MissingPermission")
    fun lastKnownLocation(): Location? {
        _state.value.location?.let { return it }
        if (!::app.isInitialized) return null
        val lm = app.getSystemService(LocationManager::class.java) ?: return null
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    fun errorText(e: Exception): String =
        if (e is TomTomException) e.message.orEmpty() else "Beklenmeyen hata: ${e.message}"

    private const val TAG = "NavSession"
    private const val LIGHT_SHOW_M = 200.0
    /** Işığın birkaç metre ötesine geçince sıradakine geçilir. */
    private const val LIGHT_PASSED_M = 5.0
    private const val STOP_MPS = 1.0f
    private const val GO_MPS = 3.0f
    private const val AUTO_NEAR_M = 30.0
    private const val AUTO_MIN_STOP_MS = 5_000L
}
