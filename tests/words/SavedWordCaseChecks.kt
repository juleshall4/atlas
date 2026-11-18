package com.atlas.engine

import android.view.KeyEvent.META_SHIFT_ON
import com.atlas.keyboard.Composer
import com.atlas.keyboard.Editor
import com.atlas.keyboard.Field
import com.atlas.keyboard.Words
import java.util.concurrent.atomic.AtomicBoolean

private const val DAY = 20000

fun main() {
    Typing().apply {
        repeat(3) { typeWord("zorbly", visible = "Zorbly") }
        expectSaved("zorbly")
    }
    Typing().apply {
        repeat(3) { typeWord("Zorbly") }
        expectSaved("Zorbly")
        typeWord("zorbly", before = "Hello. ", visible = "Zorbly")
        expectSaved("Zorbly")
    }
    Typing().apply {
        dictionary.add("Zorbly", DAY)
        typeWord("zorbly", visible = "Zorbly")
        expectSaved("Zorbly")
        typeWord("zorbly", before = "Hello ")
        expectSaved("zorbly")
    }
    Typing().apply {
        repeat(3) { typeWord("Zorbly", shiftOnce = true, visible = "zorbly") }
        expectSaved("zorbly")
    }
    Typing().apply {
        repeat(3) { typeWord("zorBly", visible = "ZorBly") }
        expectSaved("zorBly")
    }
    Typing().apply {
        // The capital from the first swipe is still on screen when the user types the correction.
        repeat(3) { correctSwipe(followingMetaState = META_SHIFT_ON) }
        expectSaved("zorbly")
    }
    Typing().apply {
        repeat(3) { correctSwipe(initialMetaState = META_SHIFT_ON) }
        expectSaved("Zorbly")
    }
    println("Saved word case checks passed")
}

private class Typing {
    val dictionary = LanguageHabits()
    private val editor = Buffer()
    private val words = object : Words {
        var recognised = ""
        override val english = true
        override fun respell(word: String) = SwipeSpelling.respell(word)
        override fun recognise(swipe: Swipe, context: String, personalise: Boolean, result: (Words.Recognition) -> Unit) =
            result(Words.Recognition(listOf(recognised), null))
        override fun complete(before: String, cancelled: AtomicBoolean, sentenceCapitalisation: Boolean, result: (List<String>) -> Unit) =
            result(emptyList())
        override fun lookAlike(context: String, word: String, next: String, typed: Boolean, result: (String?) -> Unit) = result(null)
        override fun allowed(word: String) = true
        override fun known(word: String) = false
        override fun typed(word: String, rememberCase: Boolean) = dictionary.typed(word, DAY, rememberCase)
        override fun untyped(word: String) = dictionary.untyped(word, DAY)
        override fun chosen(swipe: Swipe, context: String, recognition: Words.Recognition, word: String) = Unit
        override fun corrected(swipe: Swipe, context: String, recognition: Words.Recognition, word: String) = Unit
        override fun kept(swipe: Swipe, context: String, recognition: Words.Recognition, word: String) = Unit
    }
    private val composer = Composer(editor, words, { _, _ -> }, {}, { true }, { true }, {})
    private val field = Field(words = true, suggestions = false, learning = true, fullStops = true, starters = false,
        sentenceCapitalisation = true)

    fun typeWord(word: String, before: String = "", shiftOnce: Boolean = false, visible: String = word) {
        start(before)
        word.forEachIndexed { index, letter -> composer.type(letter.toString(), shifted = shiftOnce && index == 0) }
        composer.space(repeated = false, time = 100)
        check(editor.text == "$before$visible ") { "Expected '$before$visible ', got '${editor.text}'" }
    }

    fun correctSwipe(initialMetaState: Int = 0, followingMetaState: Int = 0) {
        start("")
        swipe("zorble", initialMetaState, 0)
        repeat(2) { composer.backspace(word = false) }
        composer.type("y")
        swipe("zorbly", followingMetaState, 200)
        check(editor.text.startsWith("Zorbly ")) { "Expected corrected swipe, got '${editor.text}'" }
    }

    private fun start(before: String) {
        editor.text = before
        composer.start(field, before.length, before.length)
    }

    private fun swipe(word: String, metaState: Int, time: Long) {
        words.recognised = word
        composer.swipe(Swipe(floatArrayOf(0f), floatArrayOf(0f), floatArrayOf(0f)), time, time + 100, metaState)
    }

    fun expectSaved(word: String) {
        val saved = dictionary.spellings(DAY).toList()
        check(saved == listOf(word)) { "Expected '$word' in Saved words, got $saved" }
    }
}

private class Buffer : Editor {
    var text = ""
    private val identity = Editor.Identity(Any(), "com.atlas.test", 0)
    override fun identity() = identity
    override fun state(result: (Editor.State?) -> Unit) = result(Editor.State(text, "", text.length, text.length))
    override fun before() = text
    override fun after() = ""
    override fun replace(count: Int, text: String, after: Int): Boolean {
        this.text = this.text.dropLast(count) + text
        return true
    }
    override fun delete(word: Boolean, selectionStart: Int, selectionEnd: Int): Int {
        text = text.dropLast(1)
        return 1
    }
    override fun enter() = Editor.Enter.Rejected
}
