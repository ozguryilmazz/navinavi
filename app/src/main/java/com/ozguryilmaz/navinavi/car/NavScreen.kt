package com.ozguryilmaz.navinavi.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
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
                        }
                        CarToast.makeText(carContext, msg, CarToast.LENGTH_LONG).show()
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
        when {
            s.navigating -> {
                strip.addAction(action(R.string.stop_nav) { NavSession.stop() })
                b.setNavigationInfo(routingInfo(s))
                s.progress?.let { b.setDestinationTravelEstimate(estimate(it)) }
            }
            dest != null -> {
                val r = s.route
                val text = when {
                    s.calculating -> carContext.getString(R.string.calculating)
                    r != null -> summary(r)
                    else -> carContext.getString(R.string.no_route)
                }
                b.setNavigationInfo(MessageInfo.Builder(dest.name).setText(text).build())
                if (r != null && !s.calculating) strip.addAction(action(R.string.start_nav) { NavSession.start(carContext) })
                strip.addAction(action(R.string.cancel) { NavSession.clear() })
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
        val ri = RoutingInfo.Builder().setCurrentStep(step(next), CarGuidance.distance(p.distanceToNext))
        // Sıradaki iki manevra birbirine yakınsa ikincisini de önceden göster.
        val after = p.afterNext
        if (after != null && after.routeOffsetMeters - next.routeOffsetMeters < 200) ri.setNextStep(step(after))
        return ri.build()
    }

    private fun step(ins: Instruction): Step =
        Step.Builder(ins.plainText().ifEmpty { carContext.getString(R.string.go_straight) })
            .setManeuver(CarGuidance.maneuver(carContext, ins))
            .build()

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

    private fun action(title: Int, onClick: () -> Unit): Action =
        Action.Builder().setTitle(carContext.getString(title)).setOnClickListener { onClick() }.build()
}
