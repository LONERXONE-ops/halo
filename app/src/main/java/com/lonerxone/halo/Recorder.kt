package com.lonerxone.halo

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import java.io.File
import java.nio.ByteBuffer

/**
 * Invisible: asks for the microphone if the chosen sound needs it, then shows
 * Android's "Start recording?" dialog and hands the answer to [ScreenRecorder].
 */
class RecordActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val audio = Prefs.get(this).getString(Prefs.REC_AUDIO, "device")
        if (audio != "none" && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        } else {
            askCapture()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // Without the microphone permission the recording simply has no sound.
        askCapture()
    }

    private fun askCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (_: Exception) {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            ScreenRecorder.start(this, resultCode, data)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQ_MIC = 1
        private const val REQ_CAPTURE = 2

        fun launch(c: Context) {
            c.startActivity(Intent(c, RecordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
    }
}

/**
 * Halo's screen recorder.
 *
 * The picture is recorded by Android's MediaRecorder straight from a mirror
 * of the screen (hardware encoder, so it's light on the phone). Sound:
 *  - "Microphone": recorded by the same MediaRecorder, one file.
 *  - "Phone sound": what apps play (games, videos, music — apps can opt
 *    out), captured on its own thread, encoded to AAC, and joined to the
 *    picture when you stop.
 * Pause leaves a seamless cut (no frozen stretch). The video goes to
 * Movies/Halo in your gallery. A 3-2-1 countdown runs on the island first so
 * Android's dialog is gone before recording starts.
 */
class ScreenRecorder : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var audio: DeviceAudio? = null
    private var videoTmp: File? = null
    private var audioTmp: File? = null
    private var outUri: Uri? = null
    private var outFd: android.os.ParcelFileDescriptor? = null
    private var recording = false
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_PAUSE -> togglePause()
            ACTION_STOP -> finishRecording()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        if (projection != null) return
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA) ?: return stopSelf()
        val mode = Prefs.get(this).getString(Prefs.REC_AUDIO, "device") ?: "device"
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val useMic = mode == "mic" && micOk
        // Android needs the service in the foreground (as a screen-capture
        // service) before it hands over the capture.
        try {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                if (useMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(NOTE_ID, notification(paused = false), type)
        } catch (e: Exception) {
            Debug.note("Recorder: couldn't start (${e.javaClass.simpleName})")
            stopSelf()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (p == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        projection = p
        // Stopped from Android's own "stop sharing" chip, or the screen capture was taken away.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { finishRecording() }
            }
        }, main)
        // 3-2-1 on the island, then go.
        val until = SystemClock.elapsedRealtime() + COUNTDOWN_MS
        IslandStore.setRec(RecInfo(KEY, own = true, runningSince = 0L, bankedMs = 0L, paused = false, countdownUntil = until))
        main.postDelayed({ startCapture(useMic, mode == "device" && micOk) }, COUNTDOWN_MS)
    }

    private fun startCapture(useMic: Boolean, deviceSound: Boolean) {
        val p = projection ?: return
        if (stopping) return
        // Size from the quality you picked, in your screen's shape.
        val wm = getSystemService(WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        val q = Quality.of(Prefs.get(this).getString(Prefs.REC_QUALITY, Quality.HD.id))
        val (w, h, used) = Quality.sizeFor(q, bounds.width(), bounds.height())
        if (used != q) {
            IslandStore.showPop("${q.label} not supported", "Using ${used.label}", Colors.ORANGE, R.drawable.ic_info, 2_400L)
        }
        val dpi = (resources.displayMetrics.densityDpi * (minOf(w, h).toFloat() / minOf(bounds.width(), bounds.height()))).toInt().coerceAtLeast(120)

        val name = "Halo_" + android.text.format.DateFormat.format("yyyyMMdd_HHmmss", System.currentTimeMillis()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Halo")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = try {
            contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } catch (_: Exception) {
            null
        }
        if (uri == null) return fail("couldn't create the video file")
        outUri = uri
        val fd = try {
            contentResolver.openFileDescriptor(uri, "rw")
        } catch (_: Exception) {
            null
        } ?: return fail("couldn't open the video file")
        outFd = fd

        // With phone sound, the picture goes to a temporary file first and the
        // sound is joined to it at the end.
        val vtmp = if (deviceSound) File(cacheDir, "rec_video.mp4").also { it.delete() } else null
        videoTmp = vtmp
        val mr = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            if (useMic) mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (useMic) {
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioEncodingBitRate(128_000)
                mr.setAudioSamplingRate(44_100)
            }
            mr.setVideoSize(w, h)
            mr.setVideoFrameRate(FPS)
            // About 0.2 bits per pixel per frame: sharp text and smooth games without huge files.
            mr.setVideoEncodingBitRate(used.bitrate)
            if (vtmp != null) mr.setOutputFile(vtmp) else mr.setOutputFile(fd.fileDescriptor)
            mr.prepare()
        } catch (e: Exception) {
            mr.release()
            return fail("recorder setup failed (${e.javaClass.simpleName})")
        }
        recorder = mr
        try {
            display = p.createVirtualDisplay("Halo recording", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mr.surface, null, null)
            mr.start()
        } catch (e: Exception) {
            return fail("couldn't start (${e.javaClass.simpleName})")
        }
        if (deviceSound) {
            val atmp = File(cacheDir, "rec_audio.m4a").also { it.delete() }
            audioTmp = atmp
            audio = DeviceAudio(p, atmp).also { if (!it.start()) audio = null }
        }
        recording = true
        IslandStore.setRec(RecInfo(KEY, own = true, runningSince = SystemClock.elapsedRealtime(), bankedMs = 0L, paused = false))
    }

    private fun togglePause() {
        val r = IslandStore.rec ?: return
        val mr = recorder ?: return
        if (!r.own || !recording) return
        val now = SystemClock.elapsedRealtime()
        try {
            if (r.paused) {
                mr.resume()
                audio?.paused = false
                IslandStore.setRec(r.copy(paused = false, runningSince = now))
            } else {
                mr.pause()
                audio?.paused = true
                IslandStore.setRec(r.copy(paused = true, bankedMs = r.elapsedMs(), runningSince = 0L))
            }
        } catch (_: Exception) {
        }
        nm().notify(NOTE_ID, notification(IslandStore.rec?.paused == true))
    }

    private fun finishRecording() {
        if (stopping) return
        stopping = true
        main.removeCallbacksAndMessages(null)
        val wasRecording = recording
        recording = false
        IslandStore.setRec(null)
        val mr = recorder
        recorder = null
        var ok = wasRecording
        try {
            mr?.stop()
        } catch (_: Exception) {
            ok = false // stopped before a single frame was written
        }
        mr?.release()
        display?.release()
        display = null
        val a = audio
        audio = null
        val p = projection
        projection = null
        // The joining and tidying can take a moment: off the main thread.
        Thread {
            a?.stop()
            try {
                p?.stop()
            } catch (_: Exception) {
            }
            val uri = outUri
            val fd = outFd
            var saved = false
            if (ok && uri != null && fd != null) {
                saved = try {
                    val v = videoTmp
                    if (v != null) join(v, audioTmp?.takeIf { it.length() > 0L && a?.wroteAnything == true }, fd.fileDescriptor)
                    true
                } catch (e: Exception) {
                    Debug.note("Recorder: joining failed (${e.javaClass.simpleName})")
                    false
                }
            }
            try {
                fd?.close()
            } catch (_: Exception) {
            }
            videoTmp?.delete()
            audioTmp?.delete()
            if (uri != null) {
                try {
                    if (saved) {
                        contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    } else {
                        contentResolver.delete(uri, null, null)
                    }
                } catch (_: Exception) {
                }
            }
            main.post {
                if (saved && uri != null) {
                    IslandStore.showPop("Saved to gallery", "", Colors.GREEN, R.drawable.ic_record, 2_600L, kind = "saved")
                    savedNotice(uri)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    private fun fail(why: String) {
        Debug.note("Recorder: $why")
        IslandStore.showPop("Recording", "Failed", Colors.RED, R.drawable.ic_record, 2_500L)
        recording = false
        finishRecording()
    }

    override fun onDestroy() {
        if (!stopping) finishRecording()
        super.onDestroy()
    }

    // ----- Joining picture and sound -----

    /** Copies the video track of [video] and the audio track of [audio] into one MP4 at [out]. */
    private fun join(video: File, audio: File?, out: java.io.FileDescriptor) {
        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val ve = MediaExtractor().apply { setDataSource(video.path) }
        val ae = audio?.let { f -> MediaExtractor().apply { setDataSource(f.path) } }
        try {
            val vTrack = trackOf(ve, "video/") ?: throw IllegalStateException("no video")
            ve.selectTrack(vTrack)
            val vOut = muxer.addTrack(ve.getTrackFormat(vTrack))
            val aTrack = ae?.let { trackOf(it, "audio/") }
            val aOut = if (ae != null && aTrack != null) {
                ae.selectTrack(aTrack)
                muxer.addTrack(ae.getTrackFormat(aTrack))
            } else {
                -1
            }
            muxer.start()
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val videoEnd = copy(ve, muxer, vOut, buf, info, Long.MAX_VALUE)
            // Sound never runs past the end of the picture.
            if (aOut >= 0 && ae != null) copy(ae, muxer, aOut, buf, info, videoEnd)
            muxer.stop()
        } finally {
            muxer.release()
            ve.release()
            ae?.release()
        }
    }

    private fun trackOf(ex: MediaExtractor, prefix: String): Int? {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
        }
        return null
    }

    /** Returns the last sample time copied. */
    private fun copy(ex: MediaExtractor, muxer: MediaMuxer, track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo, until: Long): Long {
        var last = 0L
        while (true) {
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n < 0) break
            val t = ex.sampleTime
            if (t > until) break
            info.set(0, n, t, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(track, buf, info)
            last = t
            ex.advance()
        }
        return last
    }

    // ----- Notifications -----

    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun channel() {
        if (nm().getNotificationChannel(CHANNEL) == null) {
            nm().createNotificationChannel(NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            })
        }
    }

    private fun notification(paused: Boolean): Notification {
        channel()
        fun action(act: String, label: String, req: Int) = Notification.Action.Builder(
            null, label,
            PendingIntent.getService(this, req, Intent(this, ScreenRecorder::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(if (paused) "Recording paused" else "Recording your screen")
            .setContentText("Halo")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(action(ACTION_PAUSE, if (paused) "Resume" else "Pause", 1))
            .addAction(action(ACTION_STOP, "Stop", 2))
            .build()
    }

    private fun savedNotice(uri: Uri) {
        channel()
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(this, 3, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            nm().notify(
                SAVED_ID,
                Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_record)
                    .setContentTitle("Screen recording saved")
                    .setContentText("Tap to watch · Movies/Halo")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        const val KEY = "halo:rec"
        private const val ACTION_START = "com.lonerxone.halo.rec.START"
        private const val ACTION_PAUSE = "com.lonerxone.halo.rec.PAUSE"
        private const val ACTION_STOP = "com.lonerxone.halo.rec.STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL = "recording"
        private const val NOTE_ID = 7301
        private const val SAVED_ID = 7302
        private const val COUNTDOWN_MS = 3_000L
        private const val FPS = 30

        fun start(c: Context, code: Int, data: Intent) {
            c.startForegroundService(Intent(c, ScreenRecorder::class.java).setAction(ACTION_START).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data))
        }

        fun pauseOrResume(c: Context) {
            c.startService(Intent(c, ScreenRecorder::class.java).setAction(ACTION_PAUSE))
        }

        fun stop(c: Context) {
            c.startService(Intent(c, ScreenRecorder::class.java).setAction(ACTION_STOP))
        }
    }
}

/**
 * Recording quality. The size follows your screen's shape: 720p and 1080p
 * set the short side; 4K sets the long side to 3840. Each has its own
 * bit rate, which is what decides how big the files are.
 */
enum class Quality(val id: String, val label: String, val bitrate: Int, val mbPerMin: Int) {
    // Screens are mostly still between changes, so these rates stay sharp
    // while keeping files small. MB a minute includes the sound.
    SD("720", "720p", 2_500_000, 20),
    HD("1080", "1080p", 5_000_000, 38),
    UHD("2160", "4K", 14_000_000, 105);

    companion object {
        fun of(id: String?) = values().firstOrNull { it.id == id } ?: HD

        /** Width, height, and the quality actually used (lower if this phone's encoder can't do it). */
        fun sizeFor(q: Quality, screenW: Int, screenH: Int): Triple<Int, Int, Quality> {
            var cur = q
            while (true) {
                val short = minOf(screenW, screenH).toFloat()
                val long = maxOf(screenW, screenH).toFloat()
                val f = when (cur) {
                    SD -> 720f / short
                    HD -> 1080f / short
                    UHD -> 3840f / long
                }
                var w = (screenW * f).toInt()
                var h = (screenH * f).toInt()
                w -= w % 16
                h -= h % 16
                if (supported(w, h) || cur == SD) return Triple(w, h, cur)
                cur = if (cur == UHD) HD else SD
            }
        }

        private fun supported(w: Int, h: Int): Boolean = try {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities.let { v ->
                        v.isSizeSupported(w, h) || v.isSizeSupported(h, w)
                    }
            }
        } catch (_: Exception) {
            true
        }
    }
}

/**
 * What the phone is playing (games, videos, music), captured through the
 * screen capture and encoded to AAC in its own file. While paused the sound
 * is read and thrown away, and timestamps come from the amount of sound kept,
 * so it lines up with the picture's seamless pause.
 */
private class DeviceAudio(private val projection: MediaProjection, private val out: File) {
    @Volatile var paused = false
    @Volatile private var running = false
    @Volatile var wroteAnything = false
        private set
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxing = false

    @Suppress("MissingPermission")
    fun start(): Boolean = try {
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min, CHUNK) * 4)
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 2)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        record = rec
        codec = enc
        muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        rec.startRecording()
        running = true
        thread = Thread({ loop() }, "halo-rec-audio").apply { start() }
        true
    } catch (e: Exception) {
        Debug.note("Recorder: phone sound unavailable (${e.javaClass.simpleName})")
        release()
        false
    }

    private fun loop() {
        val rec = record ?: return
        val enc = codec ?: return
        val pcm = ByteArray(CHUNK)
        val info = MediaCodec.BufferInfo()
        var keptBytes = 0L
        try {
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                if (paused) continue
                val idx = enc.dequeueInputBuffer(10_000L)
                if (idx >= 0) {
                    val buf = enc.getInputBuffer(idx) ?: continue
                    buf.clear()
                    val len = minOf(n, buf.remaining())
                    buf.put(pcm, 0, len)
                    val pts = keptBytes * 1_000_000L / (RATE * 4L)
                    enc.queueInputBuffer(idx, 0, len, pts, 0)
                    keptBytes += len
                }
                drain(enc, info, false)
            }
            // End of stream.
            val idx = enc.dequeueInputBuffer(20_000L)
            if (idx >= 0) enc.queueInputBuffer(idx, 0, 0, keptBytes * 1_000_000L / (RATE * 4L), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(enc, info, true)
        } catch (_: Exception) {
        }
    }

    private fun drain(enc: MediaCodec, info: MediaCodec.BufferInfo, toEnd: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 1_500L
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, if (toEnd) 10_000L else 0L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!toEnd || SystemClock.uptimeMillis() > deadline) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val m = muxer ?: return
                    track = m.addTrack(enc.outputFormat)
                    m.start()
                    muxing = true
                }
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && muxing && !config && info.size > 0) {
                        muxer?.writeSampleData(track, buf, info)
                        wroteAnything = true
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Stops and finishes the file. Blocks briefly; call off the main thread. */
    fun stop() {
        running = false
        try {
            thread?.join(3_000L)
        } catch (_: Exception) {
        }
        release()
    }

    private fun release() {
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        record?.release()
        record = null
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        codec?.release()
        codec = null
        try {
            if (muxing) muxer?.stop()
        } catch (_: Exception) {
        }
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        muxer = null
        muxing = false
    }

    companion object {
        const val RATE = 44_100
        const val CHUNK = 4096
    }
}
