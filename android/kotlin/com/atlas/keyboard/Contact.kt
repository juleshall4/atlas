package com.atlas.keyboard

import android.view.KeyEvent
import com.atlas.engine.Swipe
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Turns one finger contact on the touchpad into a tap, a swipe or nothing.
 *
 * Pressing a physical key makes the keyboard driver power the touchpad off and report a lift, so a
 * contact that overlaps a key press is never typing. The key event and that lift reach the keyboard
 * through different windows and can arrive in either order, so a finished contact is held for
 * [SETTLE_MILLIS] before it counts, and any key press from its start until [SETTLE_MILLIS] after its lift
 * cancels it.
 *
 * New contacts are also ignored for a short time after each key event, so a finger that brushes the
 * keys between presses does not type. Delete and a second Space press in a row add no pause.
 *
 * Typing lasts until [TYPING_MILLIS] after the last key event. While typing, swipes still type, but they do not
 * vibrate, cursor mode waits, and a contact that begins then cannot tap a key, so a finger that brushes the
 * keys between presses stays quiet. The finger
 * that pushed Space is the one exception: a contact that begins while typing after a Space press cannot
 * start cursor mode at all.
 */
class Contact {
    sealed interface Outcome {
        data class Tap(val key: String, val endedAt: Long) : Outcome
        data class Swipe(val points: List<Sample>, val startedAt: Long, val endedAt: Long) : Outcome
    }

    private sealed interface State {
        data object Idle : State
        /** A contact that cannot type: it began during a key press or was interrupted. */
        data object Ignored : State
        /** [typing]: it began while typing, so it cannot tap a key. */
        class Touching(val first: Sample, val typing: Boolean) : State {
            val points = arrayListOf(first)
            val pickup = Pickup(first)
            var travel = 0f
        }
    }

    class Settlement internal constructor(
        val deadline: Long,
        internal val outcome: Outcome?,
        internal val startedAt: Long,
        internal val endedAt: Long,
    ) {
        internal var cancelled = false
    }

    private var state: State = State.Idle
    private val pending = ArrayDeque<Settlement>()
    private val heldKeys = HashSet<Int>()
    private var blockedUntil = Long.MIN_VALUE
    /** Typing that ended with Space, so a contact that begins before then is the finger that pushed it. */
    private var spaceTypingUntil = Long.MIN_VALUE
    private var lastKeyCode = 0
    private var doubleSpace = false

    /** Alt or Sym is on, so swipes turn strip pages: they can be shorter and start on any key. */
    var paging = false

    /**
     * Whether the current contact could start cursor mode once typing ends: no key was down since it began,
     * and it is not the finger that pushed Space. The short pause that stops typing does not stop it.
     */
    var canStartCursor = false
        private set

    /** When typing ends: [TYPING_MILLIS] after the last key event. */
    var typingUntil = Long.MIN_VALUE
        private set

    /** Whether the current contact could still type, with how far it has moved in key widths. */
    val travel: Float? get() = (state as? State.Touching)?.travel

    val points: List<Sample> get() = (state as? State.Touching)?.points ?: emptyList()

    fun down(sample: Sample) {
        val free = sample.valid && heldKeys.isEmpty()
        canStartCursor = free && sample.time >= spaceTypingUntil
        state = if (free && sample.time >= blockedUntil) State.Touching(sample, sample.time < typingUntil) else State.Ignored
    }

    fun move(samples: List<Sample>) {
        val touching = state as? State.Touching ?: return
        for (sample in samples) {
            if (!sample.valid || sample.time < touching.points.last().time
                || sample.time - touching.first.time > Swipe.MAX_DURATION_MILLIS
                || touching.points.size >= Swipe.MAX_POINTS) {
                state = State.Ignored
                return
            }
            touching.points += sample
            touching.pickup.add(sample, touching.points.size - 1)
            val dx = (sample.x - touching.first.x) * 10
            val dy = (sample.y - touching.first.y) * 4
            touching.travel = max(touching.travel, hypot(dx, dy))
        }
    }

    /** Each ended contact owns its deadline, independently of the next contact. */
    fun up(samples: List<Sample>, now: Long): Settlement? {
        move(samples)
        val touching = state as? State.Touching
        state = State.Idle
        canStartCursor = false
        if (touching == null) return null
        val last = touching.points.last()
        val settlement = Settlement(now + SETTLE_MILLIS, classify(touching), touching.first.time, last.time)
        if (pending.size == MAX_PENDING_CONTACTS) pending.removeFirst()
        pending.addLast(settlement)
        return settlement
    }

    fun cancel() {
        cancelActive()
        pending.clear()
    }

    fun cancelActive() {
        state = State.Idle
        canStartCursor = false
    }

    fun settle(contact: Settlement, now: Long): Outcome? {
        if (now < contact.deadline || !pending.remove(contact)) return null
        return contact.outcome.takeUnless { contact.cancelled }
    }

    fun key(code: Int, down: Boolean, time: Long) {
        if (down) {
            heldKeys += code
            for (contact in pending) if (time in contact.startedAt..contact.endedAt + SETTLE_MILLIS) contact.cancelled = true
            if (state is State.Touching) state = State.Ignored
            canStartCursor = false
            doubleSpace = code == KeyEvent.KEYCODE_SPACE && lastKeyCode == code
            lastKeyCode = code
        } else heldKeys -= code
        typingUntil = max(typingUntil, time + TYPING_MILLIS)
        if (code == KeyEvent.KEYCODE_SPACE) spaceTypingUntil = typingUntil
        if (code == KeyEvent.KEYCODE_DEL || doubleSpace && code == KeyEvent.KEYCODE_SPACE) return
        blockedUntil = max(blockedUntil, time + KEY_COOLDOWN_MILLIS)
    }

    fun releaseKeys() {
        heldKeys.clear()
        cancel()
    }

    private fun classify(touching: State.Touching): Outcome? {
        val points = touching.points
        val first = points.first()
        val last = points.last()
        if (touching.travel >= if (paging) PAGE_KEYS else SWIPE_KEYS) {
            if (paging) return Outcome.Swipe(points.toList(), first.time, last.time)
            touching.pickup.finish()
            val entry = touching.pickup.entry
            if (entry != null) return Outcome.Swipe(listOf(entry) + points.subList(touching.pickup.entryIndex, points.size), first.time, last.time)
            if (Layout.isLetter(Layout.keyAt(first.x, first.y))) return Outcome.Swipe(points.toList(), first.time, last.time)
            return null
        }
        if (touching.typing) return null
        val key = Layout.keyAt(first.x, first.y)
        return if (key.isNotEmpty() && key == Layout.keyAt(last.x, last.y)) Outcome.Tap(key, last.time) else null
    }

    /**
     * A swipe that starts on a key other than a letter begins at the first letter the path clearly
     * travels through, ignoring shallow grazes of a corner on the way.
     */
    private class Pickup(first: Sample) {
        var entry: Sample? = null
        var entryIndex = -1
        private val enabled = Layout.keyAt(first.x, first.y).let { it.isNotEmpty() && !Layout.isLetter(it) }
        private var previous = first
        private var pending = 0.toChar()
        private var evidence = 0f
        private var closest = 0f
        private var entryDx = 0f
        private var entryDy = 0f
        private var turning = 0f
        private var pendingIndex = 0
        private var pendingEntry: Sample? = null

        fun add(sample: Sample, index: Int) {
            if (!enabled || entry != null) {
                previous = sample
                return
            }
            val dx = (sample.x - previous.x) * 10
            val dy = (sample.y - previous.y) * 4
            val distance = hypot(dx, dy)
            // Integrating along the path keeps the decision independent of the sensor's event rate.
            val steps = max(1, ceil(distance / SAMPLE_DISTANCE).toInt())
            for (step in 1..steps) {
                if (entry != null) break
                val t = (step - .5f) / steps
                val x = previous.x + (sample.x - previous.x) * t
                val y = previous.y + (sample.y - previous.y) * t
                val key = Layout.keyAt(x, y)
                val letter = if (Layout.isLetter(key)) key[0].lowercaseChar() else 0.toChar()
                if (letter != pending) {
                    pending = letter
                    evidence = 0f
                    closest = Float.POSITIVE_INFINITY
                    turning = 0f
                    entryDx = if (distance == 0f) 0f else dx / distance
                    entryDy = if (distance == 0f) 0f else dy / distance
                    pendingIndex = index
                    pendingEntry = Sample(x, y, previous.time + ((sample.time - previous.time) * t).toLong())
                }
                if (letter == 0.toChar()) continue
                val (centreX, centreY) = Layout.centre(letter)
                val cx = (x - centreX) * 10
                val cy = (y - centreY) * 4
                // Travel near an edge still counts, so the whole sensor stays usable.
                val weight = .25f + .75f * max(0f, 1 - 4 * (cx * cx + cy * cy))
                evidence += distance / steps * weight
                closest = min(closest, hypot(cx, cy))
                turning = if (distance > 0 && (dx * entryDx + dy * entryDy) / distance < STRAIGHT) turning + distance / steps else 0f
                if (evidence >= REQUIRED_EVIDENCE && (closest <= .3f || turning >= .15f)) commit()
            }
            previous = sample
        }

        /** Stopping inside a letter also shows intent, including at an outer edge. */
        fun finish() {
            if (entry == null && pending != 0.toChar() && evidence >= REQUIRED_EVIDENCE) commit()
        }

        private fun commit() {
            entry = pendingEntry
            entryIndex = pendingIndex
        }
    }

    companion object {
        const val SETTLE_MILLIS = 30L
        /** Movement in key widths before a contact becomes a swipe. */
        const val SWIPE_KEYS = .6f
        /** How far a swipe must go, in keys across or rows down, to turn a strip page or jump an emoji group. */
        const val PAGE_KEYS = .45f
        private const val KEY_COOLDOWN_MILLIS = 50L
        /** How long typing lasts after any key press or release, Delete and Space included. */
        private const val TYPING_MILLIS = 400L
        private const val SAMPLE_DISTANCE = .025f
        private const val REQUIRED_EVIDENCE = .12f
        private const val STRAIGHT = .819152f
        const val MAX_PENDING_CONTACTS = 16
    }
}
