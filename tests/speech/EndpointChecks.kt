package com.atlas.speech

import com.atlas.speech.Endpoint.State

private const val FRAME = 50
private const val ROOM = 0.002f
private const val VOICE = 0.1f

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual != expected) throw AssertionError("$name: got $actual, expected $expected")
}

/** Feeds [millis] of frames at [level] and returns the last state. */
private fun Endpoint.feed(level: Float, millis: Int): State {
    var state = state
    repeat(millis / FRAME) { state = frame(level) }
    return state
}

fun main() {
    Endpoint(FRAME).run {
        feed(ROOM, 300)
        check("speech counts after a moment", feed(VOICE, 200), State.Speaking)
        check("a breath does not end it", feed(ROOM, 700), State.Speaking)
        feed(VOICE, 1000)
        check("a long pause ends it", feed(ROOM, Endpoint.PAUSE_MILLIS), State.Finished)
    }
    Endpoint(FRAME).run {
        feed(ROOM, 300)
        check("a click is not speech", feed(VOICE, 50), State.Waiting)
        check("nothing said ends it", feed(ROOM, Endpoint.WAIT_MILLIS), State.Silent)
    }
    Endpoint(FRAME).run {
        feed(0.02f, 300)
        check("a loud room is not speech", feed(0.025f, 1000), State.Waiting)
        check("speech over a loud room counts", feed(0.2f, 300), State.Speaking)
    }
    Endpoint(FRAME).run {
        feed(ROOM, 300)
        feed(VOICE, 200)
        check("long speech stops at the limit", feed(VOICE, Endpoint.LONGEST_MILLIS), State.Finished)
    }
    println("Endpoint checks passed")
}
