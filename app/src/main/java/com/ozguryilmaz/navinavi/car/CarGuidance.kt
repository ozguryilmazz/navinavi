package com.ozguryilmaz.navinavi.car

import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.core.graphics.drawable.IconCompat
import com.ozguryilmaz.navinavi.Instruction
import com.ozguryilmaz.navinavi.R

/** TomTom manevra kodlarını Android Auto'nun manevra türlerine ve ok ikonlarına çevirir. */
object CarGuidance {

    fun maneuver(context: CarContext, ins: Instruction): Maneuver {
        val exit = ins.roundaboutExit?.takeIf { it >= 1 }
        val (type, icon) = when (ins.maneuver) {
            "TURN_RIGHT" -> Maneuver.TYPE_TURN_NORMAL_RIGHT to R.drawable.ic_turn_right
            "TURN_LEFT" -> Maneuver.TYPE_TURN_NORMAL_LEFT to R.drawable.ic_turn_left
            "KEEP_RIGHT" -> Maneuver.TYPE_KEEP_RIGHT to R.drawable.ic_turn_slight_right
            "KEEP_LEFT" -> Maneuver.TYPE_KEEP_LEFT to R.drawable.ic_turn_slight_left
            "BEAR_RIGHT" -> Maneuver.TYPE_TURN_SLIGHT_RIGHT to R.drawable.ic_turn_slight_right
            "BEAR_LEFT" -> Maneuver.TYPE_TURN_SLIGHT_LEFT to R.drawable.ic_turn_slight_left
            "SHARP_RIGHT" -> Maneuver.TYPE_TURN_SHARP_RIGHT to R.drawable.ic_turn_sharp_right
            "SHARP_LEFT" -> Maneuver.TYPE_TURN_SHARP_LEFT to R.drawable.ic_turn_sharp_left
            "MOTORWAY_EXIT_RIGHT", "TAKE_EXIT" -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT to R.drawable.ic_turn_slight_right
            "MOTORWAY_EXIT_LEFT" -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT to R.drawable.ic_turn_slight_left
            "ENTRANCE_RAMP" -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT to R.drawable.ic_turn_slight_right
            // Türkiye'de sağdan trafik: U dönüşü sola, dönel kavşak saat yönünün tersine.
            "MAKE_UTURN", "TRY_MAKE_UTURN" -> Maneuver.TYPE_U_TURN_LEFT to R.drawable.ic_turn_uturn
            "ROUNDABOUT_RIGHT", "ROUNDABOUT_LEFT", "ROUNDABOUT_CROSS", "ROUNDABOUT_BACK" ->
                (if (exit != null) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW) to
                    R.drawable.ic_roundabout
            "ARRIVE", "WAYPOINT_REACHED" -> Maneuver.TYPE_DESTINATION to R.drawable.ic_flag
            "ARRIVE_RIGHT", "WAYPOINT_RIGHT" -> Maneuver.TYPE_DESTINATION_RIGHT to R.drawable.ic_flag
            "ARRIVE_LEFT", "WAYPOINT_LEFT" -> Maneuver.TYPE_DESTINATION_LEFT to R.drawable.ic_flag
            "DEPART" -> Maneuver.TYPE_DEPART to R.drawable.ic_turn_straight
            else -> Maneuver.TYPE_STRAIGHT to R.drawable.ic_turn_straight
        }
        val b = Maneuver.Builder(type).setIcon(icon(context, icon))
        if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW && exit != null) b.setRoundaboutExitNumber(exit)
        return b.build()
    }

    fun icon(context: CarContext, res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(context, res)).build()

    /** Telefondaki formatDistance ile aynı yuvarlama. */
    fun distance(meters: Double): Distance = when {
        meters < 100 -> Distance.create(((meters / 10).toInt() * 10).toDouble(), Distance.UNIT_METERS)
        meters < 1000 -> Distance.create((((meters + 25) / 50).toInt() * 50).toDouble(), Distance.UNIT_METERS)
        meters < 10_000 -> Distance.create(meters / 1000, Distance.UNIT_KILOMETERS_P1)
        else -> Distance.create(meters / 1000, Distance.UNIT_KILOMETERS)
    }
}
