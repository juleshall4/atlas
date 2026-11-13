package com.atlas.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.DynamicsProcessing
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Owns the output-mix effect that tunes the speaker. One per process. */
object Engine {
    private const val PREFS = "speaker"

    /** Pre-EQ band upper edges (Hz). Each gain applies up to its edge. */
    private val cutoffs = floatArrayOf(
        150f, 220f, 290f, 360f, 450f, 560f, 700f, 880f, 1100f, 1400f,
        1800f, 2300f, 2900f, 3700f, 4700f, 6000f, 7600f, 9600f, 12000f, 20000f,
    )

    /** Pre-EQ gains (dB), measured where your face is with the phone held upright. */
    private val gains = floatArrayOf(
        -30f, -8f, 10.4f, 11f, 10.8f, -1.6f, 7.4f, 8.1f, -1.2f, -4.8f,
        -10.9f, -10.3f, -6.9f, -7.9f, -3.7f, -3.4f, 2.3f, -0.6f, 3.7f, 2.4f,
    )

    /** One compressor band: upper edge (Hz), attack and release (ms), ratio, threshold and makeup gain (dB). */
    private class Band(val edge: Float, val attack: Float, val release: Float, val ratio: Float, val threshold: Float, val gain: Float)

    /** Bass threshold by media volume, in DynamicsProcessing's band-level units (a full-scale tone reads about −21). */
    private val bassThreshold = sortedMapOf(0 to -22f, 7 to -22f, 9 to -25f, 11 to -26f, 13 to -26f, 15 to -29f)

    private fun bands(volume: Int) = listOf(
        Band(700f, 3f, 100f, 8f, interpolate(bassThreshold, volume), 4f),
        Band(4700f, 3f, 100f, 2f, -30f, 4f),
        Band(20000f, 1f, 80f, 2f, -32f, 4f),
    )

    private fun interpolate(table: java.util.SortedMap<Int, Float>, level: Int): Float {
        val volume = level.coerceIn(table.firstKey(), table.lastKey())
        val below = table.headMap(volume + 1).lastKey()
        val above = table.tailMap(volume).firstKey()
        if (above == below) return table.getValue(below)
        val t = (volume - below).toFloat() / (above - below)
        return table.getValue(below) + (table.getValue(above) - table.getValue(below)) * t
    }

    private val media = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private lateinit var app: Context
    private lateinit var audio: AudioManager
    private var effect: DynamicsProcessing? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var volumeReceiver: BroadcastReceiver? = null

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        audio = app.getSystemService(AudioManager::class.java)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun music(context: Context) = prefs(context).getBoolean("music", false)

    fun calls(context: Context) = prefs(context).getBoolean("calls", false)

    /** Whether the service should run: for music, calls or both. */
    fun wanted(context: Context) = music(context) || calls(context)

    /** Called by the service when it starts. */
    @Synchronized
    fun start() {
        watchDevices()
        if (music(app)) attach()
        reroute()
    }

    /** Called by the service when it stops. */
    @Synchronized
    fun stop() {
        effect?.release()
        effect = null
        deviceCallback?.let(audio::unregisterAudioDeviceCallback)
        deviceCallback = null
        playbackCallback?.let(audio::unregisterAudioPlaybackCallback)
        playbackCallback = null
        volumeReceiver?.let(app::unregisterReceiver)
        volumeReceiver = null
        Calls.releaseAll()
    }

    @Synchronized
    fun setMusic(on: Boolean) {
        save("music", on)
        if (on) {
            attach()
        } else {
            effect?.release()
            effect = null
        }
    }

    @Synchronized
    fun setCalls(on: Boolean) {
        save("calls", on)
        if (on) reroute() else Calls.releaseAll()
    }

    private fun attach() {
        if (effect == null) {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, cutoffs.size, true, 3, false, 0, true,
            ).build()
            effect = runCatching { DynamicsProcessing(100, 0, config) }
                .onFailure { Log.w("Atlas", "speaker effect: ${it.message}") }.getOrNull() ?: return
        }
        apply()
        applyEnabled()
    }

    private fun apply() {
        val dp = effect ?: return
        val eq = DynamicsProcessing.Eq(true, true, cutoffs.size)
        cutoffs.indices.forEach { eq.setBand(it, DynamicsProcessing.EqBand(true, cutoffs[it], gains[it])) }
        // The compressor keeps loud bass within what the driver can take; the limiter stops clipping.
        val mbcBands = bands(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        val mbc = DynamicsProcessing.Mbc(true, true, mbcBands.size)
        mbcBands.forEachIndexed { i, b ->
            mbc.setBand(i, DynamicsProcessing.MbcBand(true, b.edge, b.attack, b.release, b.ratio, b.threshold, 6f, -90f, 1f, 0f, b.gain))
        }
        val limiter = DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f)
        runCatching {
            dp.setPreEqAllChannelsTo(eq)
            dp.setMbcAllChannelsTo(mbc)
            dp.setLimiterAllChannelsTo(limiter)
        }.onFailure { Log.w("Atlas", "speaker effect: ${it.message}") }
    }

    private fun applyEnabled() {
        val dp = effect ?: return
        // The curve only suits this speaker, so headphones and Bluetooth are left alone.
        val on = audio.getAudioDevicesForAttributes(media).any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        runCatching { if (dp.enabled != on) dp.enabled = on }.onFailure { Log.w("Atlas", "speaker effect: ${it.message}") }
    }

    private fun watchDevices() {
        if (deviceCallback != null) return
        val handler = Handler(Looper.getMainLooper())
        deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = reroute()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = reroute()
        }.also { audio.registerAudioDeviceCallback(it, handler) }
        // Switching between connected outputs adds no device, but playing tracks report the new one.
        playbackCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = reroute()
        }.also { audio.registerAudioPlaybackCallback(it, handler) }
        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                synchronized(Engine) { apply() }
            }
        }.also { app.registerReceiver(it, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), Context.RECEIVER_NOT_EXPORTED) }
    }

    /** Routing settles shortly after a device change, so check again a moment later. */
    private fun reroute() {
        val handler = Handler(Looper.getMainLooper())
        listOf(0L, 300L, 1000L).forEach { delay ->
            handler.postDelayed({
                synchronized(this) { applyEnabled() }
                if (calls(app)) Calls.sync(app, audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            }, delay)
        }
    }

    private fun save(key: String, on: Boolean) {
        prefs(app).edit().putBoolean(key, on).apply()
    }
}
