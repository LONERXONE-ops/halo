package com.lonerxone.halo

import android.content.Context

/**
 * The buttons beside "Notifications" in the notification center: up to four,
 * chosen in Settings → On the island.
 *
 * Android only lets the system flip things like mobile data, Bluetooth and
 * airplane mode, so for those Halo does what you would: it opens Quick
 * Settings, taps the right tile, and closes it again (through its
 * accessibility service). If the tile can't be found it opens the matching
 * settings page instead.
 */
object Controls {
    class Def(val id: String, val icon: Int, val label: String, val tile: List<String> = emptyList())

    /** What a button shows: on (white), off (graphite), or null for one-shot actions. */
    class State(val def: Def, val on: Boolean?)

    val ALL = listOf(
        Def("data", R.drawable.ic_data, "Mobile data", listOf("mobile data", "cellular data", "data")),
        Def("wifi", R.drawable.ic_wifi, "Wi-Fi", listOf("wi-fi", "wifi", "wlan")),
        Def("bluetooth", R.drawable.ic_bluetooth, "Bluetooth", listOf("bluetooth")),
        Def("airplane", R.drawable.ic_airplane, "Airplane mode", listOf("airplane", "flight mode", "aeroplane")),
        Def("screenshot", R.drawable.ic_screenshot, "Screenshot"),
        Def("record", R.drawable.ic_record, "Screen record"),
        Def("torch", R.drawable.ic_flashlight, "Flashlight"),
        Def("dnd", R.drawable.ic_moon, "Do Not Disturb"),
        Def("rotate", R.drawable.ic_rotate, "Auto-rotate"),
        Def("location", R.drawable.ic_location, "Location", listOf("location")),
        Def("hotspot", R.drawable.ic_hotspot, "Hotspot", listOf("mobile hotspot", "hotspot")),
    )

    const val KEY = "nc_controls"
    const val MAX = 4
    private const val DEFAULT = "data,screenshot,record,bluetooth"

    fun chosenIds(c: Context): List<String> =
        Prefs.get(c).getString(KEY, DEFAULT)!!.split(',').filter { id -> ALL.any { it.id == id } }.distinct().take(MAX)

    fun chosen(c: Context): List<Def> = chosenIds(c).mapNotNull { id -> ALL.firstOrNull { it.id == id } }

    fun set(c: Context, ids: List<String>) {
        Prefs.get(c).edit().putString(KEY, ids.take(MAX).joinToString(",")).apply()
    }
}
