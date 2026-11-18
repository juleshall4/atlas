package com.atlas.keyboard

import android.view.KeyEvent

/**
 * Hyper plus a letter opens that letter's app. Hold Hyper and push the letter, or tap Hyper and push the
 * letter within [TAP_MILLIS]. Two Hyper presses within [Modifiers.DOUBLE_PRESS_MILLIS] start speech to text.
 * Hyper itself never reaches apps, and a letter that Hyper takes never types, even with no app set.
 */
class HyperKey {
    sealed interface Action {
        /** Not a shortcut key: the app or keyboard gets it. */
        data object Pass : Action
        /** Kept from apps, with nothing else to do. */
        data object Take : Action
        /** Kept from apps: open the app for this letter, if it has one. */
        data class Open(val letter: Char) : Action
        /** Kept from apps: Hyper was tapped twice. */
        data object Twice : Action
    }

    private var held = false
    /** The letter was pushed while Hyper was held, so letting go of Hyper does not make it ready again. */
    private var usedWhileHeld = false
    /** This press of Hyper was the second tap, so letting go does nothing. */
    private var cancelling = false
    private var tappedUntil = Long.MIN_VALUE
    private var pressedAt = 0L
    private val taken = HashSet<Int>()

    fun key(hyper: Int, code: Int, down: Boolean, repeat: Int, time: Long): Action {
        if (code == hyper) return hyper(down, repeat, time)
        if (code !in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            if (down) tappedUntil = Long.MIN_VALUE
            return Action.Pass
        }
        if (!down) return if (taken.remove(code)) Action.Take else Action.Pass
        if (repeat > 0) return if (code in taken) Action.Take else Action.Pass
        val tapped = time <= tappedUntil
        tappedUntil = Long.MIN_VALUE
        if (!held && !tapped) return Action.Pass
        if (held) usedWhileHeld = true
        taken += code
        return Action.Open('A' + (code - KeyEvent.KEYCODE_A))
    }

    private fun hyper(down: Boolean, repeat: Int, time: Long): Action {
        if (down) {
            if (repeat > 0 || held) return Action.Take
            held = true
            usedWhileHeld = false
            val sincePress = time - pressedAt
            pressedAt = time
            if (time <= tappedUntil) {
                tappedUntil = Long.MIN_VALUE
                cancelling = true
                if (sincePress in 0L until Modifiers.DOUBLE_PRESS_MILLIS) return Action.Twice
            }
            return Action.Take
        }
        held = false
        val tap = !usedWhileHeld && !cancelling
        cancelling = false
        if (tap) tappedUntil = time + TAP_MILLIS
        return Action.Take
    }

    /** Forgets held keys, for when key events stop arriving. */
    fun reset() {
        held = false
        usedWhileHeld = false
        cancelling = false
        tappedUntil = Long.MIN_VALUE
        taken.clear()
    }

    companion object {
        /** Time from letting go of a tapped Hyper to the letter. Vim and leader keys use the same. */
        const val TAP_MILLIS = 1000L
    }
}
