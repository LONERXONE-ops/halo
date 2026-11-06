package com.lonerxone.halo

import android.media.AudioManager

/**
 * Every short system moment the island shows, calibrated in one place:
 * what it says, its colour, its icon and how long it stays. Kept short
 * on purpose (iOS shows these for about two seconds): the island should
 * flick out, say it, and tuck back in.
 *
 * Kinds (how [IslandView] draws them):
 *  - "toggle": icon on the left, a little switch on the right that flips.
 *  - "ringer": icon on the left, the mode's word on the right in its colour.
 *  - "device": Bluetooth gear; the name on the right and a linked dot.
 *  - "wifi": arcs that light up (or fade out when it drops).
 *  - "battery": the level counting up beside a battery that fills.
 *  - "charge": the same with a bolt; tap it for the charging card.
 *  - "buds": earbuds' battery as a ring that fills.
 *  - "saved": a tick drawing itself.
 */
object Moments {
    // How long each kind stays (ms).
    const val TOGGLE_MS = 1_700L
    const val RINGER_MS = 1_700L
    const val DEVICE_MS = 2_500L
    const val WIFI_MS = 2_000L
    const val BATTERY_MS = 3_000L
    const val CRITICAL_MS = 4_200L
    const val CHARGE_MS = 3_200L
    const val BUDS_MS = 2_800L

    fun toggle(name: String, on: Boolean, colour: Int, icon: Int) =
        IslandStore.showPop(name, if (on) "On" else "Off", colour, icon, TOGGLE_MS, kind = "toggle")

    fun ringer(mode: Int) = when (mode) {
        AudioManager.RINGER_MODE_SILENT -> IslandStore.showPop("Silent", "Silent", Colors.RED, R.drawable.ic_bell_off, RINGER_MS, kind = "ringer")
        AudioManager.RINGER_MODE_VIBRATE -> IslandStore.showPop("Vibrate", "Vibrate", Colors.PURPLE, R.drawable.ic_vibrate, RINGER_MS, kind = "ringer")
        AudioManager.RINGER_MODE_NORMAL -> IslandStore.showPop("Ring", "Ring", Colors.INK, R.drawable.ic_bell, RINGER_MS, kind = "ringer")
        else -> Unit
    }

    fun device(name: String, connected: Boolean) =
        IslandStore.showPop(if (connected) "Connected" else "Disconnected", name.take(22), if (connected) Colors.BLUE else Colors.GREY, R.drawable.ic_bluetooth, DEVICE_MS, kind = "device")

    fun wifi(connected: Boolean) =
        IslandStore.showPop("Wi-Fi", if (connected) "Connected" else "Disconnected", if (connected) Colors.BLUE else Colors.GREY, R.drawable.ic_wifi, WIFI_MS, kind = "wifi")

    fun battery(pct: Int, critical: Boolean) =
        IslandStore.showPop("Low battery", "$pct%", if (critical) Colors.RED else Colors.ORANGE, R.drawable.ic_battery_low, if (critical) CRITICAL_MS else BATTERY_MS, kind = "battery")

    fun charged() =
        IslandStore.showPop("Charged", "100%", Colors.GREEN, R.drawable.ic_battery_full, BATTERY_MS, kind = "battery")

    fun charging(pct: Int) =
        IslandStore.showPop("Charging", "$pct%", Colors.GREEN, R.drawable.ic_bolt, CHARGE_MS, kind = "charge")

    fun buds(name: String, pct: Int) =
        IslandStore.showPop(name.take(16), "$pct%", if (pct <= 20) Colors.RED else Colors.INK, R.drawable.ic_headphones, BUDS_MS, kind = "buds")
}
