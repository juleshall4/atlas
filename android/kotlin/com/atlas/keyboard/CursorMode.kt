package com.atlas.keyboard

import kotlin.math.hypot
import kotlin.math.pow

/**
 * Rest a finger on Space to start cursor mode, then lean it towards either end of Space to move the
 * cursor that way, like a joystick. The whole bottom row sets the speed: just inside the end of Space
 * creeps for placing the cursor exactly, and the far edges of the keyboard race across long text. The rest keeps
 * swipes and grazes that cross Space out of cursor mode: the finger must stay within [REST_KEYS] of
 * where it landed for [REST_MILLIS]. Like a swipe, a brief slip off the touchpad doesn't end it: the
 * keyboard holds it through the slip recovery period, and the finger carries on where it lands if that is
 * near the bottom row. A finger that comes back higher up types as usual.
 * Distances are in key widths, as in [Contact].
 */
class CursorMode {
    private enum class State { Idle, Resting, Active, Lifted }

    private var state = State.Idle
    private var landed: Sample? = null
    private var position: Sample? = null
    /** When the finger reached the edge of the keyboard, for the build-up to full speed. */
    private var edgeSince: Long? = null

    val active: Boolean get() = state == State.Active

    /** The finger slipped off during cursor mode and may still come back. */
    val lifted: Boolean get() = state == State.Lifted

    /** Whether this contact landed on Space and may start cursor mode once it has rested. */
    fun down(sample: Sample): Boolean {
        val onSpace = sample.valid && Layout.keyAt(sample.x, sample.y) == Layout.SPACE
        state = if (onSpace) State.Resting else State.Idle
        landed = sample.takeIf { onSpace }
        position = landed
        return onSpace
    }

    /** Starts cursor mode if the finger is still resting at [now]. */
    fun rest(now: Long): Boolean {
        val first = landed ?: return false
        if (state != State.Resting || now - first.time < REST_MILLIS) return false
        state = State.Active
        return true
    }

    /** Starts cursor mode at once, with no rest, if [sample] is on Space. */
    fun hold(sample: Sample): Boolean {
        if (!sample.valid || Layout.keyAt(sample.x, sample.y) != Layout.SPACE) return false
        state = State.Active
        landed = sample
        position = sample
        return true
    }

    /** The finger left the touchpad; cursor mode waits for it to come back. */
    fun lift() {
        if (state != State.Active) return
        state = State.Lifted
        position = null
    }

    /** The finger came back after a slip near the bottom row, carrying on cursor mode from where it lands. */
    fun resume(sample: Sample): Boolean {
        if (state != State.Lifted || !sample.valid || sample.y < BOTTOM_ROW_TOP) return false
        state = State.Active
        position = sample
        return true
    }

    fun move(samples: List<Sample>) {
        for (sample in samples) {
            if (!sample.valid) continue
            if (state == State.Resting && distance(landed!!, sample) > REST_KEYS) state = State.Idle
            position = sample
        }
    }

    /** Which way the finger is leaning: -1 left, 1 right, 0 resting in the middle of Space. */
    fun direction(): Int {
        val depth = depth() ?: return 0
        return if (depth.first) -1 else 1
    }

    /**
     * Letters a second for the current lean at [now], or 0 in the middle. The speed follows [SPEEDS],
     * growing by an even factor between each pair of points, so every bit further feels like a step up:
     * slow and precise just off the middle, and quick by the key beside Space. The range ends at ○ and fn;
     * holding there or further out builds up to [FASTEST_PER_SECOND] over [RAMP_MILLIS]. With [words], it is
     * words a second instead, from [WORD_SPEEDS] up to [FASTEST_WORDS_PER_SECOND], since a word is a bigger step.
     */
    fun speed(now: Long, words: Boolean = false): Float {
        val speeds = if (words) WORD_SPEEDS else SPEEDS
        val fastest = if (words) FASTEST_WORDS_PER_SECOND else FASTEST_PER_SECOND
        val depth = depth()?.second ?: run { edgeSince = null; return 0f }
        val lean = depth.coerceAtMost(MAX_DEPTH)
        var upper = 1
        while (upper < speeds.size - 1 && lean > speeds[upper].first) upper++
        val (fromLean, fromSpeed) = speeds[upper - 1]
        val (toLean, toSpeed) = speeds[upper]
        val reached = fromSpeed * (toSpeed / fromSpeed).pow((lean - fromLean) / (toLean - fromLean))
        if (lean < MAX_DEPTH * AT_EDGE) {
            edgeSince = null
            return reached
        }
        val since = edgeSince ?: now.also { edgeSince = it }
        val ramp = ((now - since).toFloat() / RAMP_MILLIS).coerceIn(0f, 1f)
        // Eased so the build-up starts gently and settles into full speed.
        return reached + (fastest - reached) * ramp * ramp * (3 - 2 * ramp)
    }

    fun end() {
        edgeSince = null
        state = State.Idle
        landed = null
        position = null
    }

    /** Whether the finger leans left, and how far into the edge zone it is, or null in the middle. */
    private fun depth(): Pair<Boolean, Float>? {
        val sample = position ?: return null
        if (!active || sample.y < BOTTOM_ROW_TOP) return null
        val column = sample.x * 10
        val left = SPACE_LEFT + EDGE_KEYS - column
        val right = column - (SPACE_RIGHT - EDGE_KEYS)
        return when {
            left > 0 -> true to left
            right > 0 -> false to right
            else -> null
        }
    }

    private fun distance(a: Sample, b: Sample) = hypot((b.x - a.x) * 10, (b.y - a.y) * 4)

    companion object {
        const val REST_MILLIS = 180L
        private const val REST_KEYS = .3f
        /** The ends of Space, in key widths from the left edge of the keyboard. */
        private val SPACE_LEFT = Layout.bottomWidths.take(3).sum()
        private val SPACE_RIGHT = SPACE_LEFT + Layout.bottomWidths[3]
        /** How far in from each end of Space the cursor starts moving. */
        private const val EDGE_KEYS = 1.35f
        /** A little above the bottom row still counts, so a finger drifting up keeps control. */
        private const val BOTTOM_ROW_TOP = .7f
        /**
         * From where moving starts to the middle of ○ or fn, the second key from each end. Leaning further
         * counts the same. Space sits in the middle of the row, so both sides match.
         */
        private val MAX_DEPTH = SPACE_LEFT + EDGE_KEYS - Layout.bottomWidths[0] - Layout.bottomWidths[1] / 2
        /** How far out counts as reaching the edge. */
        private const val AT_EDGE = .97f
        private const val RAMP_MILLIS = 1000f
        /** Letters a second at each lean, in key widths past the still spot. */
        private val SPEEDS = listOf(
            0f to 1.5f, // just off the middle
            .5f to 3f, // the precise stretch ends
            EDGE_KEYS to 14f, // the end of Space
            EDGE_KEYS + .65f to 22f, // the key beside Space
            MAX_DEPTH to 28f, // ○ or fn, where holding builds up to full speed
        )
        private const val FASTEST_PER_SECOND = 52f
        /**
         * Words a second at each lean, for growing a selection. Slow near the middle, so one word at a time is
         * easy to stop on, and about a line a second at the edge.
         */
        private val WORD_SPEEDS = listOf(
            0f to .8f,
            .5f to 1.5f,
            EDGE_KEYS to 3.5f,
            EDGE_KEYS + .65f to 5f,
            MAX_DEPTH to 7f,
        )
        private const val FASTEST_WORDS_PER_SECOND = 12f
    }
}
