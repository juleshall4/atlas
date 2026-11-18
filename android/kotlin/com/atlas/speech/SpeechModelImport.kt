package com.atlas.speech

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** The import belongs to Atlas, so leaving Model does not stop it. State is read on the main thread. */
internal object SpeechModelImport {
    data class Progress(val percent: Int, val secondsLeft: Int? = null)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    var progress: Progress? = null
        private set
    var failed = false
        private set

    fun listen(change: () -> Unit): () -> Unit {
        listeners += change
        return { listeners -= change }
    }

    fun start(context: Context, uri: Uri, closePicker: () -> Unit): Boolean {
        if (progress != null) return false
        failed = false
        progress = Progress(0)
        changed()
        Thread({
            var opened = false
            val started = SystemClock.elapsedRealtime()
            val succeeded = try {
                SpeechModels.add(context, uri, opened = {
                    opened = true
                    main.post(closePicker)
                }) { percent ->
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val remaining = if (elapsed >= 5000 && percent >= 5) {
                        (elapsed * (100 - percent) / percent / 1000).toInt()
                    } else null
                    main.post {
                        progress = Progress(percent, remaining)
                        changed()
                    }
                }
                true
            } catch (error: Exception) {
                Log.w("Atlas", "Could not add the speech model", error)
                false
            }
            main.post {
                failed = !succeeded
                progress = null
                if (!opened) closePicker()
                changed()
            }
        }, "Atlas speech model import").start()
        return true
    }

    private fun changed() = listeners.forEach { it() }
}
