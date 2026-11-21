package com.atlas.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.atlas.engine.TypingSettings
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.sqrt

/**
 * One go at speech to text: listens until you stop speaking, then turns what it heard into text.
 * The model loads while you speak. Calls to [Listener] come on the main thread.
 */
class Dictation(private val context: Context, private val listener: Listener) {
    interface Listener {
        /** How loud the last frame was, from 0 to 1. */
        fun heard(level: Float)
        /** Listening ended, and Atlas is turning the speech into text. */
        fun writing()
        /** The text, or null when nothing was said. */
        fun done(text: String?)
        fun failed(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopping = false
    @Volatile private var cancelled = false
    private var thread: Thread? = null

    /** The microphone is on. After this, Atlas writes what it heard. */
    @Volatile var listening = false
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        check(thread == null) { "Dictation runs once." }
        val model = SpeechModels.chosen(context) ?: return listener.failed("Add a speech model in Atlas.")
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return listener.failed("Allow the microphone in Atlas.")
        val language = language(model)
        val loading = Recognizers.load(context, model, language)
        listening = true
        thread = Thread({ run(loading) }, "Atlas dictation").apply { start() }
    }

    /** Stops listening and writes what was heard so far. */
    fun stop() {
        stopping = true
    }

    /** Stops listening and types nothing. */
    fun cancel() {
        cancelled = true
        stopping = true
    }

    private fun language(model: SpeechModels.Model): String {
        val typing = TypingSettings.language(context)
        return if (typing in model.languages) typing else "en"
    }

    @SuppressLint("MissingPermission")
    private fun run(loading: Future<OfflineRecognizer>) {
        val frame = SAMPLE_RATE * FRAME_MILLIS / 1000
        val size = maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), frame * 4)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, size)
        } catch (error: RuntimeException) {
            Log.w("Atlas", "Could not open the microphone", error)
            listening = false
            return post { listener.failed(NO_MICROPHONE) }
        }
        val audio = FloatArrayBuilder()
        val endpoint = Endpoint(FRAME_MILLIS)
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) return post { listener.failed(NO_MICROPHONE) }
            record.startRecording()
            val buffer = ShortArray(frame)
            while (!stopping) {
                var filled = 0
                while (filled < frame && !stopping) {
                    val count = record.read(buffer, filled, frame - filled)
                    if (count < 0) return post { listener.failed(NO_MICROPHONE) }
                    filled += count
                }
                var sum = 0.0
                for (i in 0 until filled) {
                    val sample = buffer[i] / 32768f
                    audio.add(sample)
                    sum += sample * sample
                }
                val level = if (filled == 0) 0f else sqrt(sum / filled).toFloat()
                post { listener.heard(level) }
                when (endpoint.frame(level)) {
                    Endpoint.State.Finished -> break
                    Endpoint.State.Silent -> return post { listener.done(null) }
                    else -> Unit
                }
            }
        } catch (error: RuntimeException) {
            Log.w("Atlas", "Could not record speech", error)
            return post { listener.failed(NO_MICROPHONE) }
        } finally {
            listening = false
            try { record.stop() } catch (_: IllegalStateException) {}
            record.release()
        }
        if (cancelled) return
        // Stopped before any speech counted: there is nothing to write.
        if (endpoint.state == Endpoint.State.Waiting) return post { listener.done(null) }
        post { listener.writing() }
        val text = try {
            Recognizers.transcribe(loading, audio.toArray()) { cancelled }
        } catch (error: Exception) {
            Log.w("Atlas", "Could not turn speech into text", error)
            return post { listener.failed("Could not turn your speech into text.") }
        }
        post { listener.done(text.trim().ifEmpty { null }) }
    }

    private fun post(action: () -> Unit) {
        main.post { if (!cancelled) action() }
    }

    private class FloatArrayBuilder {
        private var values = FloatArray(SAMPLE_RATE * 10)
        private var size = 0
        fun add(value: Float) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }
        fun toArray() = values.copyOf(size)
    }

    /**
     * Loaded models stay ready for a while after use, so the next go starts at once. A model takes a
     * few hundred megabytes of memory, so it goes after [KEEP_MILLIS].
     */
    private object Recognizers {
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "Atlas speech model") }
        private val main = Handler(Looper.getMainLooper())
        private var loaded: Pair<String, OfflineRecognizer>? = null
        private val release = Runnable { worker.execute { loaded?.second?.release(); loaded = null } }

        fun load(context: Context, model: SpeechModels.Model, language: String): Future<OfflineRecognizer> {
            main.removeCallbacks(release)
            main.postDelayed(release, KEEP_MILLIS)
            val folder = SpeechModels.folder(context, model)
            return worker.submit<OfflineRecognizer> {
                val key = "${model.id}:$language"
                loaded?.let { (id, recognizer) -> if (id == key) return@submit recognizer }
                loaded?.second?.release()
                loaded = null
                OfflineRecognizer(config = config(model, folder, language)).also { loaded = key to it }
            }
        }

        fun transcribe(loading: Future<OfflineRecognizer>, samples: FloatArray, cancelled: () -> Boolean): String =
            worker.submit<String> {
                if (cancelled()) return@submit ""
                val recognizer = loading.get()
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text
                } finally {
                    stream.release()
                }
            }.get()

        private fun config(model: SpeechModels.Model, folder: File, language: String): OfflineRecognizerConfig {
            fun path(name: String) = File(folder, name).absolutePath
            val modelConfig = when (model.kind) {
                SpeechModels.Kind.Canary -> OfflineModelConfig(
                    canary = OfflineCanaryModelConfig(encoder = path(SpeechModels.ENCODER), decoder = path(SpeechModels.DECODER),
                        srcLang = language, tgtLang = language, usePnc = true),
                    tokens = path(SpeechModels.TOKENS), numThreads = THREADS)
                SpeechModels.Kind.Transducer -> OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(encoder = path(SpeechModels.ENCODER),
                        decoder = path(SpeechModels.DECODER), joiner = path(SpeechModels.JOINER)),
                    tokens = path(SpeechModels.TOKENS), modelType = "nemo_transducer", numThreads = THREADS)
            }
            return OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 128),
                modelConfig = modelConfig)
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MILLIS = 50
        private const val NO_MICROPHONE = "Could not use the microphone."
        private const val THREADS = 4
        private const val KEEP_MILLIS = 5 * 60_000L
    }
}
