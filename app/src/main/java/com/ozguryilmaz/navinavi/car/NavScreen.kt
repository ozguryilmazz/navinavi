package com.ozguryilmaz.navinavi.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.ozguryilmaz.navinavi.LightRecord
import com.ozguryilmaz.navinavi.formatDistance
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ozguryilmaz.navinavi.Instruction
import com.ozguryilmaz.navinavi.NavProgress
import com.ozguryilmaz.navinavi.NavSession
import com.ozguryilmaz.navinavi.R
import com.ozguryilmaz.navinavi.Route
import com.ozguryilmaz.navinavi.plainText
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** Araba ekranının ana sayfası: harita + sıradaki manevra ya da hedef seçme düğmesi. */
class NavScreen(carContext: CarContext, private val renderer: CarMapRenderer) : Screen(carContext) {

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { NavSession.state.collect { invalidate() } }
                launch {
                    NavSession.events.collect { e ->
                        val msg = when (e) {
                            NavSession.Event.Arrived -> carContext.getString(R.string.arrived) + " 🏁"
                            is NavSession.Event.Error -> e.message
                            is NavSession.Event.Info -> e.message
                        }
                        val length = if (e is NavSession.Event.Info) CarToast.LENGTH_SHORT else CarToast.LENGTH_LONG
                        CarToast.makeText(carContext, msg, length).show()
                    }
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val s = NavSession.state.value
        val strip = ActionStrip.Builder()
        val b = NavigationTemplate.Builder()
        val dest = s.destination
        // Android Auto bu çubukta en fazla 4 düğmeye ve sadece 1 yazılı düğmeye izin veriyor;
        // diğerleri ikonlu.
        when {
            s.navigating -> {
                // Yeşil yandığı / kırmızıya döndüğü an tek dokunuş (menü, onay yok).
                strip.addAction(iconAction(R.drawable.ic_light_dot, CarColor.GREEN) {
                    NavSession.recordLight(LightRecord.Kind.GREEN)
                })
                strip.addAction(iconAction(R.drawable.ic_light_dot, CarColor.RED) {
                    NavSession.recordLight(LightRecord.Kind.RED)
                })
                strip.addAction(action(R.string.stop_nav) { NavSession.stop() })
                b.setNavigationInfo(routingInfo(s))
                s.progress?.let { b.setDestinationTravelEstimate(estimate(it)) }
            }
            dest != null -> {
                val r = s.route
                val text = when {
                    s.calculating -> carContext.getString(R.string.calculating)
                    r != null -> summary(r) + (NavSession.lightsText(s)?.let { "\n$it" } ?: "")
                    else -> carContext.getString(R.string.no_route)
                }
                b.setNavigationInfo(MessageInfo.Builder(dest.name).setText(text).build())
                if (r != null && !s.calculating) strip.addAction(action(R.string.start_nav) { NavSession.start(carContext) })
                strip.addAction(iconAction(R.drawable.ic_close, null) { NavSession.clear() })
            }
            else -> {
                strip.addAction(action(R.string.car_destination) {
                    screenManager.push(DestinationScreen(carContext, renderer))
                })
            }
        }
        b.setActionStrip(strip.build())
        return b.build()
    }

    private fun routingInfo(s: NavSession.State): RoutingInfo {
        val p = s.progress
        val next = p?.next
        if (s.rerouting || p == null || next == null) return RoutingInfo.Builder().setLoading(true).build()
        // Işığa 200 m kalınca mesafesi manevra yazısının başında gösterilir.
        val light = s.nextLight?.let {
            carContext.getString(R.string.car_light_distance, formatDistance(s.nextLightDistance))
        }
        val ri = RoutingInfo.Builder().setCurrentStep(step(next, light), CarGuidance.distance(p.distanceToNext))
        // Sıradaki iki manevra birbirine yakınsa ikincisini de önceden göster.
        val after = p.afterNext
        if (after != null && after.routeOffsetMeters - next.routeOffsetMeters < 200) ri.setNextStep(step(after))
        return ri.build()
    }

    private fun step(ins: Instruction, prefix: String? = null): Step {
        val text = ins.plainText().ifEmpty { carContext.getString(R.string.go_straight) }
        return Step.Builder(if (prefix != null) "$prefix · $text" else text)
            .setManeuver(CarGuidance.maneuver(carContext, ins))
            .build()
    }

    private fun estimate(p: NavProgress): TravelEstimate =
        TravelEstimate.Builder(
            CarGuidance.distance(p.remainingMeters),
            ZonedDateTime.now().plusSeconds(p.remainingSeconds.toLong()),
        ).setRemainingTimeSeconds(p.remainingSeconds.toLong()).build()

    private fun summary(r: Route): String {
        val min = (r.travelTimeSeconds + 30) / 60
        val time = if (min < 60) "$min dk" else "${min / 60} sa ${min % 60} dk"
        val km = String.format(java.util.Locale("tr", "TR"), "%.1f km", r.lengthMeters / 1000.0)
        return "$km · $time"
    }

    private fun iconAction(icon: Int, tint: CarColor?, onClick: () -> Unit): Action {
        val ic = CarIcon.Builder(IconCompat.createWithResource(carContext, icon))
        if (tint != null) ic.setTint(tint)
        return Action.Builder().setIcon(ic.build()).setOnClickListener { onClick() }.build()
    }

    private fun action(title: Int, onClick: () -> Unit): Action =
        Action.Builder().setTitle(carContext.getString(title)).setOnClickListener { onClick() }.build()
}
