package com.atlas.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.DynamicsProcessing
import android.util.Log
import java.util.concurrent.Executors

/**
 * Applies a voice EQ to other apps' call audio (WhatsApp and other VoIP apps) while it plays on the speaker.
 * Android hides other apps' audio session ids, so this reads them from `dumpsys audio`. That needs DUMP,
 * granted once over adb (no root):
 *   adb shell pm grant com.atlas android.permission.DUMP
 */
object Calls {
    /** Pre-EQ band upper edges (Hz), the same bands as the music profile. */
    private val cutoffs = floatArrayOf(
        150f, 220f, 290f, 360f, 450f, 560f, 700f, 880f, 1100f, 1400f,
        1800f, 2300f, 2900f, 3700f, 4700f, 6000f, 7600f, 9600f, 12000f, 20000f,
    )

    /**
     * Voice gains (dB): cut voice fundamentals below 220 Hz that only make this speaker buzz, add some body at
     * 290–880 Hz, take out the speaker's 1.4–2.9 kHz peak, keep 3.7–6 kHz for clarity, and roll off codec hiss
     * above 7.6 kHz. EQ only, no compression, so the caller's echo canceller isn't upset.
     */
    private val gains = floatArrayOf(
        -30f, -18f, -6f, 3f, 5f, 4f, 4f, 2f, 0f, -2f,
        -4f, -5f, -4f, 0f, 1f, 0f, -2f, -6f, -12f, -18f,
    )

    private val effects = mutableMapOf<Int, DynamicsProcessing>()
    private val worker = Executors.newSingleThreadExecutor()
    private val voiceCall = Regex("""state:started attr:AudioAttributes: usage=USAGE_VOICE_COMMUNICATION .*?sessionId:(\d+)""")

    fun allowed(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    private fun voiceSessions(): List<Int> {
        val dump = ProcessBuilder("dumpsys", "audio").redirectErrorStream(true).start()
        val text = dump.inputStream.bufferedReader().use { it.readText() }
        dump.waitFor()
        return voiceCall.findAll(text).map { it.groupValues[1].toInt() }.filter { it > 0 }.distinct().toList()
    }

    /** Matches effects to the call sessions playing now; `onSpeaker` bypasses them for the earpiece or a headset. */
    fun sync(context: Context, onSpeaker: Boolean) {
        if (!allowed(context)) return
        worker.execute {
            val sessions = runCatching { voiceSessions() }.getOrElse { emptyList() }
            synchronized(this) {
                // Calls can be switched off while the scan runs.
                if (!Engine.calls(context)) return@execute
                (effects.keys - sessions.toSet()).forEach { effects.remove(it)?.release() }
                sessions.forEach { session ->
                    runCatching {
                        val dp = effects.getOrPut(session) { create(session) }
                        if (dp.enabled != onSpeaker) dp.enabled = onSpeaker
                    }.onFailure { Log.w("Atlas", "call effect on $session: ${it.message}") }
                }
            }
        }
    }

    private fun create(session: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            true, cutoffs.size, false, 0, false, 0, true,
        ).build()
        return DynamicsProcessing(100, session, config).apply {
            val eq = DynamicsProcessing.Eq(true, true, cutoffs.size)
            cutoffs.indices.forEach { eq.setBand(it, DynamicsProcessing.EqBand(true, cutoffs[it], gains[it])) }
            setPreEqAllChannelsTo(eq)
            setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
        }
    }

    @Synchronized
    fun releaseAll() {
        effects.values.forEach { it.release() }
        effects.clear()
    }
}
