package com.ozguryilmaz.navinavi.car

import android.Manifest
import android.annotation.SuppressLint
import android.app.Presentation
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.ContextCompat
import com.ozguryilmaz.navinavi.NavSession
import com.ozguryilmaz.navinavi.Place
import com.ozguryilmaz.navinavi.Route
import com.ozguryilmaz.navinavi.RouteLayer
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Araba ekranına MapLibre haritası çizer. Android Auto bize sadece bir çizim yüzeyi (Surface)
 * verir; o yüzeyden sanal bir ekran (VirtualDisplay) açıp üstünde Presentation içinde
 * normal bir MapView gösteriyoruz.
 */
class CarMapRenderer(private val carContext: CarContext) : SurfaceCallback {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var routeLayer: RouteLayer? = null

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    /** Şablon kartlarının kapatmadığı alan; araç ikonu bu alanın içinde kalsın. */
    private var visibleArea: Rect? = null

    private var shownRoute: Route? = null
    private var shownDestination: Place? = null
    /** Kamera en son hangi kipe ayarlandı: null = henüz ayarlanmadı. */
    private var cameraNavigating: Boolean? = null

    fun attach() {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        release()
        val surface = surfaceContainer.surface ?: return
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        try {
            val dm = carContext.getSystemService(DisplayManager::class.java)
            val vd = dm.createVirtualDisplay(
                "NaviNaviCar", surfaceWidth, surfaceHeight, surfaceContainer.dpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            virtualDisplay = vd
            MapLibre.getInstance(carContext.applicationContext)
            val p = Presentation(carContext, vd.display)
            // TextureView kipi: harita, Presentation penceresiyle birlikte sanal ekrana çizilir.
            val options = MapLibreMapOptions.createFromAttributes(p.context).textureMode(true)
            val mv = MapView(p.context, options)
            p.setContentView(mv)
            p.show()
            presentation = p
            mapView = mv
            mv.onCreate(null)
            mv.onStart()
            mv.onResume()
            mv.getMapAsync { m -> onMapReady(m, p) }
        } catch (e: Exception) {
            Log.e(TAG, "Araba haritası açılamadı", e)
            release()
        }
    }

    private fun onMapReady(m: MapLibreMap, p: Presentation) {
        map = m
        m.uiSettings.isCompassEnabled = false
        m.cameraPosition = CameraPosition.Builder().target(LatLng(39.0, 35.0)).zoom(5.0).build()
        m.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { s ->
            if (map !== m) return@setStyle
            routeLayer = RouteLayer(s)
            shownRoute = null
            shownDestination = null
            cameraNavigating = null
            enableLocation(m, s, p)
            render(NavSession.state.value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation(m: MapLibreMap, s: Style, p: Presentation) {
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val lc = m.locationComponent
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(p.context, s).useDefaultLocationEngine(true).build()
            )
            lc.isLocationComponentEnabled = true
            // Arabada pusula anlamsız (telefon tutucuda); ok hep GPS yönünü gösterir.
            lc.renderMode = RenderMode.GPS
        } catch (e: Exception) {
            Log.e(TAG, "Konum gösterilemedi", e)
        }
    }

    /** NavSession durumunu haritaya yansıtır. */
    fun render(s: NavSession.State) {
        val m = map ?: return
        val layer = routeLayer ?: return
        val routeChanged = s.route !== shownRoute || s.destination !== shownDestination
        if (routeChanged) {
            layer.set(s.destination?.position, s.route?.points)
            shownRoute = s.route
            shownDestination = s.destination
        }
        val lc = m.locationComponent
        if (!lc.isLocationComponentActivated) return

        if (s.navigating) {
            if (cameraNavigating != true) {
                cameraNavigating = true
                lc.renderMode = RenderMode.GPS
                lc.cameraMode = CameraMode.TRACKING_GPS
                lc.zoomWhileTracking(17.0)
                lc.tiltWhileTracking(45.0)
                applyPadding()
            }
        } else {
            val r = s.route
            if (r != null && (routeChanged || cameraNavigating != false) && r.points.size >= 2) {
                // Önizleme: rotanın tamamını görünen alana sığdır.
                cameraNavigating = false
                lc.cameraMode = CameraMode.NONE
                lc.tiltWhileTracking(0.0)
                val a = area()
                val pad = 40
                val bounds = LatLngBounds.Builder().includes(r.points).build()
                m.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        bounds, a.left + pad, a.top + pad, surfaceWidth - a.right + pad, surfaceHeight - a.bottom + pad,
                    ),
                    800,
                )
            } else if (r == null && cameraNavigating != false) {
                cameraNavigating = false
                lc.cameraMode = CameraMode.TRACKING
                lc.tiltWhileTracking(0.0)
                lc.zoomWhileTracking(15.0)
                applyPadding()
            } else if (r == null && routeChanged) {
                // Hedef iptal edildi: konuma geri dön.
                lc.cameraMode = CameraMode.TRACKING
                lc.zoomWhileTracking(15.0)
            }
        }
    }

    /** Haritada konumu bulunduğum yer; araba ekranından hedef seçerken kullanılır. */
    fun currentLocation(): LatLng? {
        val l = map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.lastKnownLocation
            ?: NavSession.lastKnownLocation() ?: return null
        return LatLng(l.latitude, l.longitude)
    }

    private fun area(): Rect = visibleArea ?: Rect(0, 0, surfaceWidth, surfaceHeight)

    /** Araç ikonu görünen alanın ortasında, navigasyonda biraz aşağıda dursun. */
    private fun applyPadding() {
        val lc = map?.locationComponent ?: return
        if (!lc.isLocationComponentActivated) return
        val a = area()
        val extraTop = if (cameraNavigating == true) a.height() * 0.35 else 0.0
        lc.paddingWhileTracking(
            doubleArrayOf(
                a.left.toDouble(), a.top + extraTop,
                (surfaceWidth - a.right).toDouble(), (surfaceHeight - a.bottom).toDouble(),
            )
        )
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        applyPadding()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = release()

    fun release() {
        map = null
        routeLayer = null
        mapView?.let {
            runCatching {
                it.onPause()
                it.onStop()
                it.onDestroy()
            }
        }
        mapView = null
        runCatching { presentation?.dismiss() }
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private companion object {
        const val TAG = "CarMapRenderer"
        const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    }
}
