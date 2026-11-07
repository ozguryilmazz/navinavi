package com.ozguryilmaz.navinavi.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.ozguryilmaz.navinavi.NavSession
import kotlinx.coroutines.launch

/** Android Auto bu servise bağlanır ve araba ekranı için bir oturum ister. */
class NaviCarAppService : CarAppService() {
    // Uygulama sadece kişisel kullanım için, Play Store'da değil: her Android Auto sürümüne izin ver.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session = NaviCarSession()

    @Deprecated("Eski Android Auto sürümleri için")
    override fun onCreateSession(): Session = NaviCarSession()
}

class NaviCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        NavSession.init(carContext)
        val renderer = CarMapRenderer(carContext)
        renderer.attach()

        // Android Auto'ya "navigasyon başladı/bitti" bilgisi verilir; böylece başka navigasyon
        // uygulaması (ör. Google Haritalar) aynı anda yön tarif etmez.
        val navManager = carContext.getCarService(NavigationManager::class.java)
        navManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() = NavSession.stop()
        })
        var started = false
        lifecycleScope.launch {
            NavSession.state.collect { s ->
                renderer.render(s)
                if (s.navigating != started) {
                    started = s.navigating
                    runCatching { if (started) navManager.navigationStarted() else navManager.navigationEnded() }
                }
            }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (started) runCatching { navManager.navigationEnded() }
                renderer.release()
            }
        })
        return NavScreen(carContext, renderer)
    }
}
