package com.atlas.speech

import kotlin.math.max
import kotlin.math.min

/**
 * Decides when someone has finished speaking, from the loudness of each short frame of the microphone.
 * The quietest moments set the level of the room, and speech is clearly louder than that.
 */
class Endpoint(private val frameMillis: Int) {
    enum class State { Waiting, Speaking, Finished, Silent }

    private var floor = Float.MAX_VALUE
    private var elapsed = 0
    private var quietFor = 0
    private var spoken = 0

    var state = State.Waiting
        private set

    /** Takes the loudness of the next frame, from 0 to 1, and returns what is happening now. */
    fun frame(level: Float): State {
        if (state == State.Finished || state == State.Silent) return state
        elapsed += frameMillis
        // The floor follows quiet frames down at once and rises only slowly, so speech does not lift it.
        floor = if (level < floor) level else min(floor + (level - floor) * 0.01f, level)
        val speech = level > max(floor * SPEECH_OVER_FLOOR, QUIETEST_SPEECH)
        if (speech) {
            spoken += frameMillis
            quietFor = 0
            if (spoken >= SPEECH_MILLIS) state = State.Speaking
        } else {
            quietFor += frameMillis
            if (state == State.Waiting) spoken = 0
        }
        state = when {
            state == State.Speaking && (quietFor >= PAUSE_MILLIS || elapsed >= LONGEST_MILLIS) -> State.Finished
            state == State.Waiting && elapsed >= WAIT_MILLIS -> State.Silent
            else -> state
        }
        return state
    }

    companion object {
        /** Speech needs this much in a row to count, so a click or a bump does not. */
        const val SPEECH_MILLIS = 150
        /** A pause this long ends what you say. People pause for breath for about half of this. */
        const val PAUSE_MILLIS = 1500
        /** With no speech by now, Atlas stops listening. */
        const val WAIT_MILLIS = 6000
        const val LONGEST_MILLIS = 60_000
        private const val SPEECH_OVER_FLOOR = 3f
        /** Below this, a frame is silence however quiet the room is. */
        private const val QUIETEST_SPEECH = 0.01f
    }
}
