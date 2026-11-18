package com.atlas.keyboard

import com.atlas.engine.Swipe
import com.atlas.engine.SwipeTyping
import java.util.concurrent.atomic.AtomicBoolean

/** [Words] backed by the FUTO swipe engine and its learned habits. */
class EngineWords(private val typing: SwipeTyping) : Words {
    override val english get() = typing.english()
    override fun respell(word: String) = typing.respell(word)
    override fun allowed(word: String) = typing.wordAllowed(word)
    override fun known(word: String) = typing.knownWord(word)

    /** What the engine needs to learn from a recognised swipe later. */
    private class Learning(val prediction: SwipeTyping.Prediction, val generation: Long)

    override fun recognise(swipe: Swipe, context: String, personalise: Boolean, result: (Words.Recognition) -> Unit) {
        val generation = typing.learningGeneration()
        typing.recognise(swipe, context, personalise, object : SwipeTyping.Callback {
            override fun result(words: Array<String>, elapsed: Long, error: String?) =
                result(Words.Recognition(words.toList(), null))

            override fun result(words: Array<String>, elapsed: Long, error: String?, prediction: SwipeTyping.Prediction) =
                result(Words.Recognition(words.toList(), if (personalise) Learning(prediction, generation) else null))
        })
    }

    override fun complete(before: String, cancelled: AtomicBoolean, sentenceCapitalisation: Boolean, result: (List<String>) -> Unit) =
        typing.complete(before, cancelled, sentenceCapitalisation) { words, _, _ -> if (!cancelled.get()) result(words.toList()) }

    override fun lookAlike(context: String, word: String, next: String, typed: Boolean, result: (String?) -> Unit) =
        typing.lookAlike(context, word, next, typed) { result(it) }

    override fun typed(word: String, rememberCase: Boolean) = typing.typed(word, typing.learningGeneration(), rememberCase)
    override fun untyped(word: String) = typing.untyped(word, typing.learningGeneration())

    override fun chosen(swipe: Swipe, context: String, recognition: Words.Recognition, word: String) {
        val learning = recognition.token as? Learning ?: return
        typing.confirm(swipe, context, word, learning.generation, learning.prediction, true)
    }

    override fun corrected(swipe: Swipe, context: String, recognition: Words.Recognition, word: String) {
        val learning = recognition.token as? Learning ?: return
        typing.confirm(swipe, context, word, learning.generation, learning.prediction, false)
    }

    override fun kept(swipe: Swipe, context: String, recognition: Words.Recognition, word: String) {
        val learning = recognition.token as? Learning ?: return
        typing.retained(swipe, context, word, learning.generation, learning.prediction)
    }
}
