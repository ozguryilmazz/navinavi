package com.ozguryilmaz.navinavi

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
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
    )

    sealed interface Event {
        data object Arrived : Event
        data class Error(val message: String) : Event
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
        _state.update { it.copy(navigating = false, rerouting = false, progress = null) }
        app.stopService(Intent(app, NavigationService::class.java))
    }

    /** Hedefi ve rotayı tamamen siler. */
    fun clear() {
        stop()
        routeRequest++
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
        _state.update { it.copy(progress = p) }
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
}
