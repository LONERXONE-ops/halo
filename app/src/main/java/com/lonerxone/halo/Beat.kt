package com.lonerxone.halo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Makes the music bars follow the actual sound. Android's Visualizer reads
 * the phone's audio output (it never records the microphone, but Android
 * gates it behind the microphone permission). Three bands: bass, mids, highs.
 * If the phone won't share the output, [fresh] goes false and the bars fall
 * back to their usual bounce.
 */
object Beat {
    /** 0..1 per bar: bass, mids, highs. */
    val levels = FloatArray(3)
    private val peaks = floatArrayOf(1f, 1f, 1f)
    private var lastData = 0L
    private var lastLoud = 0L
    private var viz: Visualizer? = null

    fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** True while real levels are arriving (and the music isn't silent). */
    fun fresh(): Boolean {
        val now = SystemClock.uptimeMillis()
        return viz != null && now - lastData < 400L && now - lastLoud < 1500L
    }

    /** Starts listening while [on]; stops and frees the visualizer otherwise. */
    fun ensure(c: Context, on: Boolean) {
        if (!on || !allowed(c) || !Prefs.get(c).getBoolean(Prefs.BEAT, false)) {
            stop()
            return
        }
        if (viz != null) return
        try {
            val v = Visualizer(0)
            v.enabled = false
            val range = Visualizer.getCaptureSizeRange()
            v.captureSize = min(max(256, range[0]), range[1])
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer?, wave: ByteArray?, rate: Int) {}

                override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, rate: Int) {
                    if (fft != null) onFft(fft)
                }
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            viz = v
        } catch (_: Throwable) {
            stop()
        }
    }

    fun stop() {
        try {
            viz?.enabled = false
            viz?.release()
        } catch (_: Throwable) {
        }
        viz = null
        levels.fill(0f)
    }

    private val bands = arrayOf(1..3, 4..16, 17..60)

    private fun onFft(fft: ByteArray) {
        val bins = fft.size / 2
        var loud = false
        for (b in 0 until 3) {
            var sum = 0f
            var n = 0
            for (k in bands[b]) {
                if (k >= bins) break
                sum += hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
                n++
            }
            val avg = if (n > 0) sum / n else 0f
            if (avg > 1.5f) loud = true
            // Auto-gain: each band is measured against its own recent peak.
            peaks[b] = max(avg, peaks[b] * 0.985f).coerceAtLeast(4f)
            val target = (avg / peaks[b]).coerceIn(0f, 1f)
            // Quick attack, softer release.
            levels[b] += (target - levels[b]) * if (target > levels[b]) 0.7f else 0.25f
        }
        val now = SystemClock.uptimeMillis()
        lastData = now
        if (loud) lastLoud = now
    }
}
