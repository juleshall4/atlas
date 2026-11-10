package com.atlas.keyboard

import com.atlas.engine.Swipe
import com.atlas.engine.SwipeTyping
import com.atlas.engine.TypedPredictions
import com.atlas.engine.TypedWords
import java.util.concurrent.atomic.AtomicBoolean

/** The text around the cursor, and the edits the keyboard makes there. */
interface Editor {
    data class Identity(val connection: Any, val packageName: String?, val fieldId: Int)
    data class State(val before: String, val after: String, val selectionStart: Int, val selectionEnd: Int)
    enum class Enter { Newline, Next, Previous, Action, Rejected }

    fun identity(): Identity?
    /** A bounded query acknowledged by fresh selection metadata from the app. Null means unverified. */
    fun state(result: (State?) -> Unit)
    /** Null means that the editor cannot supply this text, rather than an empty field. */
    fun before(): String?
    fun after(): String?
    /** Deletes around the cursor, then types over any selection. False may include a partial edit. */
    fun replace(count: Int, text: String, after: Int = 0): Boolean
    /** Characters deleted before the selection; null means that the resulting edit is unverified. */
    fun delete(word: Boolean, selectionStart: Int, selectionEnd: Int): Int?
    fun enter(): Enter

    companion object {
        const val CONTEXT_LENGTH = 2048
    }
}

/** The word engine: swipe recognition, completions and learning. */
interface Words {
    class Recognition(val words: List<String>, val token: Any?)

    fun recognise(swipe: Swipe, context: String, personalise: Boolean, result: (Recognition) -> Unit)
    fun complete(before: String, cancelled: AtomicBoolean, sentenceCapitalisation: Boolean, result: (List<String>) -> Unit)
    /** [typed] words were typed letter by letter, so the earlier words have not chosen between the look-alikes yet. */
    fun lookAlike(context: String, word: String, next: String, typed: Boolean, result: (String?) -> Unit)
    fun allowed(word: String): Boolean
    fun known(word: String): Boolean
    fun typed(word: String, rememberCase: Boolean = true)
    /** Takes back the last [typed] use, because the user deleted that word. */
    fun untyped(word: String)
    fun chosen(swipe: Swipe, context: String, recognition: Recognition, word: String)
    fun corrected(swipe: Swipe, context: String, recognition: Recognition, word: String)
    fun kept(swipe: Swipe, context: String, recognition: Recognition, word: String)
}

/** Turns taps, swipes and keys into edits and suggestions for one text field. */
class Composer(
    private val editor: Editor,
    private val words: Words,
    private val later: (Long, () -> Unit) -> Unit,
    private val externalChange: () -> Unit,
    private val autoCapitalise: () -> Boolean,
    private val autoPunctuation: () -> Boolean,
    private val show: (List<String>) -> Unit,
) {
    private data class Selection(val start: Int, val end: Int)
    private data class Snapshot(val before: String?, val after: String?, val selection: Selection, val selected: Boolean) {
        val available get() = before != null && after != null
        val knownSelection get() = selection.start >= 0 && selection.end >= 0
    }

    private sealed interface Composition {
        val alternatives: List<String>

        data object None : Composition {
            override val alternatives = emptyList<String>()
        }

        data class Swiped(
            val word: String,
            val leadingSpace: String,
            val trailingSpace: Boolean,
            val swipe: Swipe,
            val context: String,
            val recognition: Words.Recognition,
            val endedAt: Long,
            val continuable: Boolean,
            val snapshot: Snapshot,
            val chosen: Boolean,
            override val alternatives: List<String>,
        ) : Composition {
            val tail get() = word + if (trailingSpace) " " else ""
            val inserted get() = leadingSpace + tail
            var finalised = false
        }

        class Typed(val prefix: String, val trailingSpace: Boolean, val snapshot: Snapshot,
                    override val alternatives: List<String>) : Composition

        /** The cursor is in an earlier swiped word, [before] and [after] letters from its ends. */
        data class Recalled(val word: Remembered, val before: Int, val after: Int, val snapshot: Snapshot) : Composition {
            override val alternatives get() = word.alternatives
        }
    }

    /** A finished word that may still change to its look-alike, such as "were" to "we're", once the next word is done. */
    private data class HeldWord(val word: String, val context: String, val inserted: String, val tail: String,
                                val snapshot: Snapshot, val typed: Boolean)

    /** A decided look-alike, applied once input pauses if the text from [start] still begins with [lead]. */
    private class Swap(val earlier: HeldWord, val replacement: String, val start: Int, val lead: String, val owner: Editor.Identity?)

    /** A swiped word still in the field, with the suggestions it offered. [start] follows the keyboard's own edits. */
    private data class Remembered(val start: Int, val word: String, val alternatives: List<String>, val swipe: Swipe,
                                  val context: String, val recognition: Words.Recognition) {
        val end get() = start + word.length
    }

    private sealed interface PendingInput
    private data class PendingSwipe(val swipe: Swipe, val startedAt: Long, val endedAt: Long, val metaState: Int) : PendingInput
    private class PendingText(var text: String, val letterTap: Boolean, val shifted: Boolean) : PendingInput
    private class PendingEdit(val action: () -> Unit, val transferable: Boolean = false) : PendingInput
    private class FullStop(val time: Long, val snapshot: Snapshot)
    private class Barrier(val identity: Editor.Identity, val navigation: Boolean = false, val command: Boolean = false)

    private var field = Field.None
    private var composition: Composition = Composition.None
    private var epoch = 0
    private var selection = Selection(-1, -1)
    private var selectionConfirmed = false
    private val expectedSelections = ArrayDeque<Selection>()
    private val pendingInput = ArrayDeque<PendingInput>()
    private var recognising = false
    private var executingQueued = false
    private var draining = false
    private var drainScheduled = false
    private var drainTicket = 0
    private var barrier: Barrier? = null
    private var identity: Editor.Identity? = null
    private var recognitionSnapshot: Snapshot? = null
    private var completion: AtomicBoolean? = null
    private var completionSnapshot: Snapshot? = null
    private var editSnapshot: Snapshot? = null
    private var automaticSpace: Snapshot? = null
    private var fullStop: FullStop? = null
    private val manualWord = TypedWords()
    private var correcting: Composition.Swiped? = null
    private var awaitingNextWord: HeldWord? = null
    /** A typed word that waits for the next word to start. */
    private var typedWord: HeldWord? = null
    /** The typed word that was just learned, which ends at [end]. Deleting into it takes the use back. */
    private class Learned(val word: String, val end: Int, val snapshot: Snapshot)
    private var learned: Learned? = null
    private var swap: Swap? = null
    private var lookAlikeChangedAt = -1
    private val remembered = ArrayList<Remembered>()
    private var rememberedField: Pair<String?, Int>? = null

    var liftOffMillis = 0

    val hasPendingInput get() = recognising || barrier != null || drainScheduled || pendingInput.isNotEmpty()
    val awaitingDestination get() = barrier?.navigation == true

    fun start(field: Field, selectionStart: Int, selectionEnd: Int) {
        val next = editor.identity()
        val transition = barrier
        val carries = transition?.navigation == true && next != null
            && !next.packageName.isNullOrEmpty() && next.packageName == transition.identity.packageName
            && (next.connection !== transition.identity.connection || next.fieldId != transition.identity.fieldId)
        val carried = if (carries) pendingInput.takeWhile {
            it is PendingText || it is PendingEdit && it.transferable
        } else emptyList()
        this.field = field
        selection = Selection(selectionStart, selectionEnd)
        selectionConfirmed = selectionStart >= 0 && selectionEnd >= 0
        reset()
        lookAlikeChangedAt = -1
        identity = next
        // Another field, or a password, starts with nothing remembered.
        val owner = next?.let { it.packageName to it.fieldId }
        if (!field.learning || owner != rememberedField) remembered.clear()
        rememberedField = owner
        pendingInput.addAll(carried)
        recogniseNext()
        predict()
    }

    fun stop(terminal: Boolean = false) {
        if (terminal && sameIdentity(identity, editor.identity())
            && (composition as? Composition.Swiped)?.chosen == true) leave()
        field = Field.None
        if (barrier?.navigation == true) {
            composition = Composition.None
            automaticSpace = null
            fullStop = null
            editSnapshot = null
            manualWord.clear()
            correcting = null
            awaitingNextWord = null
            expectedSelections.clear()
            show(emptyList())
            return
        }
        reset()
    }

    private fun invalidate(discardInput: Boolean = true) {
        epoch++
        completion?.set(true)
        completion = null
        completionSnapshot = null
        if (discardInput) {
            barrier = null
            drainTicket++
            drainScheduled = false
            pendingInput.clear()
            recognising = false
            recognitionSnapshot = null
        }
    }

    private fun reset() {
        invalidate()
        composition = Composition.None
        automaticSpace = null
        fullStop = null
        editSnapshot = null
        manualWord.clear()
        correcting = null
        awaitingNextWord = null
        typedWord = null
        learned = null
        swap = null
        expectedSelections.clear()
        show(emptyList())
    }

    private fun closesWord(next: Char?) = next != null && (next.isWhitespace() || next in WORD_CLOSERS)

    private fun snapshot() = Snapshot(editor.before(), editor.after(), selection,
        selection.start >= 0 && selection.end >= 0 && selection.start != selection.end)

    private fun sameIdentity(first: Editor.Identity?, second: Editor.Identity?) = first != null && second != null
        && first.connection === second.connection && first.packageName == second.packageName && first.fieldId == second.fieldId

    /** Own selection notifications may be coalesced, or arrive after a later successful edit. */
    fun selectionChanged(start: Int, end: Int): Boolean {
        val waiting = barrier
        // Outgoing-field callbacks cannot resolve a navigation action; start() or its timeout does.
        if (waiting?.navigation == true) return false
        if (waiting?.command == true && sameIdentity(editor.identity(), waiting.identity)) {
            // The ordered state query acknowledges all effects of this app-handled command together.
            return false
        }
        val updated = Selection(start, end)
        val anchor = recognitionSnapshot ?: when (val current = composition) {
            is Composition.Swiped -> current.snapshot
            is Composition.Typed -> current.snapshot
            is Composition.Recalled -> current.snapshot
            Composition.None -> completionSnapshot ?: automaticSpace ?: fullStop?.snapshot ?: editSnapshot
        }
        if (updated != selection && anchor != null && snapshot() == anchor) {
            confirmState(anchor, notifyExternal = true)
            return false
        }
        val ours = expectedSelections.indexOf(updated)
        if (ours >= 0 && updated == selection && anchor != null && snapshot() == anchor) {
            repeat(ours + 1) { expectedSelections.removeFirst() }
            selectionConfirmed = true
            return false
        }
        if (updated == selection && expectedSelections.isEmpty() && (anchor == null || snapshot() == anchor)) return false
        selection = updated
        selectionConfirmed = start >= 0 && end >= 0
        reset()
        predict()
        return true
    }

    fun swipe(swipe: Swipe, startedAt: Long, endedAt: Long, metaState: Int) {
        android.util.Log.d("AtlasDiag", "composer swipe words=${field.words} pending=${pendingInput.size} recognising=$recognising barrier=${barrier?.navigation}/${barrier?.command} draining=$draining scheduled=$drainScheduled")
        if (!field.words) return
        if (!hasPendingInput) {
            invalidate()
            fullStop = null
        }
        if (enqueue(PendingSwipe(swipe, startedAt, endedAt, metaState))) recogniseNext()
    }

    private fun defer(transferable: Boolean = false, action: () -> Unit): Boolean {
        if (executingQueued || !hasPendingInput) return false
        enqueue(PendingEdit(action, transferable))
        return true
    }

    private fun enqueue(input: PendingInput): Boolean {
        if (pendingInput.size >= MAX_PENDING_INPUT) {
            // Preserve accepted ordinary keys. Under overload, shed only a surplus unstarted swipe.
            val surplus = pendingInput.lastOrNull { it is PendingSwipe && it !== pendingInput.firstOrNull() }
            if (input is PendingSwipe || surplus == null) return false
            pendingInput.remove(surplus)
        }
        pendingInput += input
        return true
    }

    private fun enqueueText(text: String, letterTap: Boolean, shifted: Boolean) {
        val units = pendingInput.sumOf { if (it is PendingText) it.text.length else 0 }
        if (units + text.length > MAX_PENDING_TEXT) return
        val last = pendingInput.lastOrNull() as? PendingText
        if (!letterTap && !shifted && last != null && !last.letterTap && !last.shifted
            && last.text.length + text.length <= MAX_TEXT_CHUNK) {
            last.text += text
        } else enqueue(PendingText(text, letterTap, shifted))
    }

    private fun scheduleDrain() {
        if (drainScheduled) return
        drainScheduled = true
        val ticket = ++drainTicket
        later(0) {
            if (ticket != drainTicket) return@later
            drainScheduled = false
            recogniseNext()
        }
    }

    /** Resolve real swipes in order, using the context produced by the preceding accepted edit. */
    private fun recogniseNext() {
        if (recognising || barrier != null || draining || drainScheduled) return
        draining = true
        val started = System.nanoTime()
        try {
            var edits = 0
            while (pendingInput.isNotEmpty() && pendingInput.first() !is PendingSwipe) {
                if (edits++ == DRAIN_BUDGET || System.nanoTime() - started >= DRAIN_NANOS) {
                    scheduleDrain()
                    return
                }
                val pending = pendingInput.removeFirst()
                executingQueued = true
                try {
                    when (pending) {
                        is PendingEdit -> pending.action()
                        is PendingText -> type(pending.text, pending.letterTap, pending.shifted)
                        is PendingSwipe -> Unit
                    }
                } finally {
                    executingQueued = false
                }
                if (barrier != null || recognising) return
            }
        } finally {
            draining = false
        }
        val pending = pendingInput.firstOrNull() as? PendingSwipe ?: return
        if (!selectionConfirmed) {
            confirmState(editSnapshot.takeIf { correcting != null })
            return
        }
        fullStop = null
        val anchor = snapshot()
        val before = anchor.before
        if (!anchor.available || !anchor.knownSelection || before == null) {
            reset()
            return
        }
        val correction = correcting
        val completed = if (correction != null || startsNewWord(before)) completeManualWord(anchor) else null
        correcting = null
        manualWord.clear()
        val previous = composition as? Composition.Swiped
        var resumes = previous != null && previous.continuable && liftOffMillis > 0
            && pending.startedAt - previous.endedAt in 0..liftOffMillis.toLong()
            && anchor == previous.snapshot && before.endsWith(previous.inserted) && !anchor.selected
        val path = if (resumes) {
            try {
                previous!!.swipe.append(pending.swipe)
            } catch (_: IllegalArgumentException) {
                resumes = false
                pending.swipe
            }
        } else pending.swipe
        if (!resumes) {
            leave()
            awaitingNextWord = null
            holdForNextWord(previous, anchor)
        }
        composition = Composition.None
        show(emptyList())
        val resumed = previous.takeIf { resumes }
        val context = resumed?.context ?: before
        val replaced = resumed?.inserted?.length ?: 0
        val leadingSpace = resumed?.leadingSpace ?: if (anchor.selected) "" else SwipeTyping.prefix(context)
        // Mid-sentence the next character may already be a space or punctuation, so another space would double up.
        val trailingSpace = resumed?.trailingSpace ?: (!anchor.selected && !closesWord(anchor.after?.firstOrNull()))
        val current = epoch
        recognising = true
        recognitionSnapshot = anchor
        words.recognise(path, context, field.learning) { recognition ->
            android.util.Log.d("AtlasDiag", "recognised ${recognition.words.take(3)} stale=${current != epoch || pendingInput.firstOrNull() !== pending} moved=${snapshot() != anchor}")
            if (current != epoch || pendingInput.firstOrNull() !== pending) return@recognise
            if (snapshot() != anchor) {
                android.util.Log.d("AtlasDiag", "anchor ${anchor} now ${snapshot()}")
                reset()
                return@recognise
            }
            recognising = false
            recognitionSnapshot = null
            pendingInput.removeFirst()
            if (recognition.words.isNotEmpty()) {
                val sentenceCase = field.sentenceCapitalisation && autoCapitalise()
                val choices = recognition.words.map {
                    SwipeTyping.capitalise(it, context + leadingSpace, pending.metaState, false, sentenceCase)
                }
                val word = choices.first()
                val inserted = leadingSpace + word + if (trailingSpace) " " else ""
                if (!edit(replaced, inserted)) { android.util.Log.d("AtlasDiag", "edit failed"); return@recognise }
                val accepted = snapshot()
                automaticSpace = accepted.takeIf { trailingSpace }
                val swiped = Composition.Swiped(word, leadingSpace, trailingSpace, path, context, recognition, pending.endedAt,
                    true, accepted, false, choices)
                composition = swiped
                remember(swiped)
                confirmState(accepted) { confirmed ->
                    if (confirmed) {
                        learnWord(completed, correction)
                        show(choices)
                        checkLookAlike()
                    }
                }
                return@recognise
            }
            learnWord(completed, correction)
            recogniseNext()
        }
    }

    /**
     * Keys wait for pending swipes, retaining the order in which the user entered them.
     * [shifted] text is from one press of Shift; at the start of a sentence it gives lowercase.
     */
    fun type(text: String, letterTap: Boolean = false, shifted: Boolean = false) {
        if (text.isEmpty()) return
        if (!executingQueued && hasPendingInput) {
            enqueueText(text, letterTap, shifted)
            return
        }
        leave()
        invalidate(false)
        val typedSpace = fullStop?.snapshot
        fullStop = null
        val anchor = snapshot()
        holdForNextWord(composition as? Composition.Swiped, anchor)
        val correction = correcting
        if (correction != null && anchor != editSnapshot) {
            correcting = null
            manualWord.clear()
        }
        val completesCorrection = correcting != null && !letterTap && text.first() in ".,!?;:)]}…"
        val finishesWord = letterTap && (correction != null || startsNewWord(anchor.before.orEmpty()))
        val completed = if (completesCorrection || finishesWord) completeManualWord(anchor) else null
        if (completesCorrection || letterTap) correcting = null
        // Like a swiped word's space, a space typed after a word moves behind the punctuation.
        val dropsSpace = (automaticSpace == anchor || typedSpace == anchor && autoPunctuation()) && anchor.before?.endsWith(" ") == true
            && text[0] in ".,!?;:)]}…"
        val before = anchor.before
        // "i." can start "i.e.", so it waits for a space to become "I.".
        val abbreviating = text[0] == '.' && before?.takeLastWhile { it.isLetter() || it == '\'' || it == '’' } == "i"
        val respelled = if (!letterTap && !dropsSpace && !abbreviating && field.fullStops && text[0] in ".,!?;:)]}…") {
            before?.let(::respell)
        } else null
        val replaced = (if (dropsSpace) 1 else 0) + (respelled?.first?.length ?: 0)
        val prefix = if (letterTap && !anchor.selected) SwipeTyping.prefix(before.orEmpty()) else ""
        val cased = if (before == null || !field.sentenceCapitalisation || !autoCapitalise()) text
        else if (shifted) SwipeTyping.uncapitalise(text, before + prefix)
        else SwipeTyping.capitalise(text, before + prefix)
        val typed = if (letterTap) "$prefix$cased " else cased
        if (!edit(replaced, (respelled?.second ?: "") + typed)) return
        if (!letterTap) checkLookAlike()
        automaticSpace = if (letterTap) snapshot() else null
        composition = Composition.None
        if (letterTap) manualWord.clear()
        else if (!completesCorrection) observe(text)
        if (!completed.isNullOrEmpty()) {
            confirmState(snapshot()) { confirmed -> if (confirmed) learnWord(respelled?.second ?: completed, correction) }
            return
        }
        predictLater()
    }

    fun symbol(symbol: String) {
        if (defer { symbol(symbol) }) return
        leave()
        invalidate(false)
        fullStop = null
        composition = Composition.None
        manualWord.clear()
        correcting = null
        awaitingNextWord = null
        val before = editor.before().orEmpty()
        val after = editor.after().orEmpty()
        val around = symbol == "&" || symbol == "="
        val trailing = around || symbol == ";" || symbol == "…" || symbol == "•" && (before.isEmpty() || before.endsWith("\n"))
        val leading = around && before.isNotEmpty() && !Character.isWhitespace(before.codePointBefore(before.length))
        val reuseSpace = trailing && after.startsWith(" ")
        val space = reuseSpace || trailing && (after.isEmpty() || !after[0].isWhitespace())
        val text = (if (leading) " " else "") + symbol + if (space) " " else ""
        if (!edit(0, text, if (reuseSpace) 1 else 0)) return
        automaticSpace = null
        predictLater()
    }

    fun space(repeated: Boolean, time: Long) {
        if (defer(transferable = true) { space(repeated, time) }) return
        leave()
        invalidate(false)
        val anchor = snapshot()
        holdForNextWord(composition as? Composition.Swiped, anchor)
        composition = Composition.None
        val before = anchor.before.orEmpty()
        val correction = correcting
        val completed = completeManualWord(anchor)
        correcting = null
        val pending = fullStop
        fullStop = null
        var respelled: Pair<String, String>? = null
        when {
            !field.fullStops || anchor.selected -> if (!edit(0, " ")) return
            automaticSpace == anchor && before.endsWith(" ") -> {
                if (!repeated && endsWord(before.dropLast(1))) fullStop = FullStop(time, anchor)
            }
            !repeated && pending != null && time - pending.time in 0..FULL_STOP_MILLIS && autoPunctuation()
                && anchor.available && anchor == pending.snapshot -> if (!edit(1, ". ")) return
            else -> {
                respelled = respell(before)
                val replaced = respelled ?: pronounBeforeFullStop(before)
                if (!edit(replaced?.first?.length ?: 0, (replaced?.second ?: "") + " ")) return
                // Read the bounded editor window after insertion; appending would exceed its limit.
                if (!repeated && endsWord(before)) fullStop = FullStop(time, snapshot())
                // The word before this one is finished now, so decide it before this word waits in turn.
                checkLookAlike()
                if (!repeated && !anchor.selected) typedWord = holdTyped(before, respelled)
            }
        }
        automaticSpace = null
        if (field.learning && !completed.isNullOrEmpty()) {
            confirmState(snapshot()) { confirmed -> if (confirmed) learnWord(respelled?.second ?: completed, correction) }
            return
        }
        predictLater()
    }

    fun backspace(word: Boolean) {
        if (defer { backspace(word) }) return
        if (!sameIdentity(identity, editor.identity())) {
            reset()
            return
        }
        // Deleting or cancelling a swipe is not evidence that the word was kept.
        val source = if (word || !field.learning) null else correcting ?: composition as? Composition.Swiped
        val expected = if (correcting != null) editSnapshot else source?.snapshot
        val anchor = source?.let { snapshot().takeIf { it == expected && !it.selected } }
        val typed = learned?.takeIf { snapshot() == it.snapshot }
        learned = null
        invalidate(false)
        composition = Composition.None
        automaticSpace = null
        fullStop = null
        awaitingNextWord = null
        typedWord = null
        swap = null
        if (word || correcting != null || source != null) manualWord.clear()
        else manualWord.edit("\b", null, false)
        correcting = null
        val previous = selection
        val removed = editor.delete(word, previous.start, previous.end)
        if (removed != null) {
            if (previous.start >= 0 && previous.end >= 0) {
                val start = minOf(previous.start, previous.end)
                val end = maxOf(previous.start, previous.end)
                shiftRemembered(start - removed, end, -(end - start + removed))
            } else remembered.clear()
            expectSelection(previous, removed, 0)
            editSnapshot = snapshot()
            if (source != null && anchor != null && removed > 0) trackCorrection(source, anchor, removed)
            typed?.let(::untypeIfDeleted)
        } else {
            // The app may have delegated or partly applied deletion. Do not replay dependent input.
            reset()
            return
        }
        // Keep the old words until the new ones arrive, so the bar does not flash empty for each deleted letter.
        predictLater()
    }

    private fun trackCorrection(source: Composition.Swiped, beforeDelete: Snapshot, removed: Int) {
        val afterDelete = editSnapshot ?: return
        val before = beforeDelete.before ?: return
        val actual = afterDelete.before ?: return
        val start = source.snapshot.selection.end - source.tail.length
        val remaining = afterDelete.selection.end - start
        if (remaining !in 1..actual.length || removed > before.length || beforeDelete.after != afterDelete.after) return
        val expected = before.dropLast(removed)
        if (if (before.length < Editor.CONTEXT_LENGTH) actual != expected else !actual.endsWith(expected)) return
        val prefix = actual.takeLast(remaining)
        if (prefix.length != TypedPredictions.prefix(prefix).length) return
        manualWord.edit(prefix, null, false)
        correcting = source
    }

    fun enter() {
        if (defer { enter() }) return
        leave()
        invalidate(false)
        composition = Composition.None
        fullStop = null
        awaitingNextWord = null
        val anchor = snapshot()
        val correction = correcting
        val completed = completeManualWord(anchor)
        correcting = null
        if (automaticSpace == anchor && anchor.before?.endsWith(" ") == true && !edit(1, "")) return
        automaticSpace = null
        val owner = editor.identity()
        if (!sameIdentity(identity, owner)) {
            reset()
            return
        }
        val current = epoch
        val result = editor.enter()
        if (current != epoch || !sameIdentity(owner, editor.identity())) return
        when (result) {
            Editor.Enter.Newline -> {
                if (!edit(0, "\n")) return
                if (!completed.isNullOrEmpty()) {
                    confirmState(snapshot()) { confirmed -> if (confirmed) learnWord(completed, correction) }
                    return
                }
            }
            Editor.Enter.Next, Editor.Enter.Previous -> {
                learnWord(completed, correction)
                waitForDestination(owner, navigation = true)
            }
            Editor.Enter.Action -> {
                learnWord(completed, correction)
                waitForDestination(owner, navigation = false)
            }
            Editor.Enter.Rejected -> reset()
        }
        show(emptyList())
        predictLater()
    }

    private fun waitForDestination(owner: Editor.Identity?, navigation: Boolean) {
        if (owner == null || !sameIdentity(identity, owner)) {
            reset()
            return
        }
        val waiting = Barrier(owner, navigation)
        barrier = waiting
        if (!navigation) pendingInput.clear()
        later(DESTINATION_TIMEOUT_MILLIS) {
            if (barrier === waiting) reset()
        }
    }

    /** App-handled keys share the edit queue and fence dependent input with an ordered app query. */
    fun command(destructive: Boolean, fieldLocal: Boolean, action: () -> Boolean) {
        if (defer { command(destructive, fieldLocal, action) }) return
        if (!destructive) leave()
        invalidate(false)
        composition = Composition.None
        automaticSpace = null
        fullStop = null
        manualWord.clear()
        correcting = null
        awaitingNextWord = null
        expectedSelections.clear()
        val owner = editor.identity()
        if (owner == null || !sameIdentity(identity, owner)) {
            reset()
            return
        }
        val waiting = Barrier(owner, command = true)
        barrier = waiting
        if (!action()) {
            reset()
            return
        }
        if (barrier !== waiting) return
        if (!fieldLocal) {
            pendingInput.clear()
            later(DESTINATION_TIMEOUT_MILLIS) { if (barrier === waiting) reset() }
            return
        }
        barrier = null
        confirmState()
    }

    /** [predictNow] skips the wait that batches fast typing, for an edit such as a tapped word that ends a burst. */
    private fun confirmState(expected: Snapshot? = null, notifyExternal: Boolean = false, predictNow: Boolean = false,
                             result: (Boolean) -> Unit = {}) {
        val owner = editor.identity()
        if (owner == null || !sameIdentity(identity, owner)) {
            reset()
            result(false)
            return
        }
        val waiting = Barrier(owner, command = true)
        val current = epoch
        barrier = waiting
        editor.state { state ->
            if (barrier !== waiting || current != epoch) return@state
            barrier = null
            if (state == null || !sameIdentity(owner, editor.identity())) {
                selection = Selection(-1, -1)
                selectionConfirmed = false
                reset()
                if (notifyExternal) externalChange()
                result(false)
                return@state
            }
            selection = Selection(state.selectionStart, state.selectionEnd)
            selectionConfirmed = true
            val actual = Snapshot(state.before, state.after, selection, selection.start != selection.end)
            if (expected != null && actual != expected) {
                reset()
                if (notifyExternal) externalChange()
                result(false)
                return@state
            }
            editSnapshot = actual
            expectedSelections.clear()
            result(true)
            recogniseNext()
            if (predictNow) predict() else predictLater()
        }
    }

    fun choose(index: Int) {
        val chosen = composition.alternatives.getOrNull(index) ?: return
        if (!words.allowed(chosen)) return
        val offered = composition
        if (defer {
            val current = composition
            if (current === offered || current is Composition.Swiped && offered is Composition.Swiped
                && current.recognition === offered.recognition) choose(index)
        }) return
        val anchor = snapshot()
        if (!anchor.available || !anchor.knownSelection) return
        val before = anchor.before ?: return
        when (val current = composition) {
            is Composition.Swiped -> {
                if (anchor != current.snapshot || !before.endsWith(current.inserted) || anchor.selected) return
                invalidate(false)
                val tail = chosen + if (current.trailingSpace) " " else ""
                if (!edit(current.tail.length, tail)) return
                val accepted = snapshot()
                val swiped = current.copy(word = chosen, continuable = false, snapshot = accepted, chosen = true)
                composition = swiped
                remember(swiped)
                automaticSpace = accepted.takeIf { current.trailingSpace }
                confirmState(accepted)
            }
            is Composition.Typed -> {
                val tail = current.prefix + if (current.trailingSpace) " " else ""
                // A starter word replaces a selection; any other word would leave it behind.
                if (anchor != current.snapshot || !before.endsWith(tail) || anchor.selected && tail.isNotEmpty()) return
                invalidate(false)
                val space = if (current.prefix.isEmpty()) SwipeTyping.prefix(before) else ""
                if (!edit(tail.length, "$space$chosen ")) return
                composition = Composition.None
                automaticSpace = snapshot()
                val correction = correcting?.takeIf { anchor == editSnapshot }
                manualWord.clear()
                correcting = null
                // Keep the old words until the next words arrive, so the bar does not flash empty.
                confirmState(snapshot(), predictNow = true) { confirmed ->
                    if (confirmed) learnWord(chosen, correction, rememberCase = false)
                }
            }
            is Composition.Recalled -> {
                if (anchor != current.snapshot || anchor.selected) return
                invalidate(false)
                val start = current.word.start
                if (!edit(current.before, chosen, current.after)) return
                val word = current.word.copy(start = start, word = chosen)
                add(word)
                manualWord.clear()
                correcting = null
                val accepted = snapshot()
                composition = current.copy(word = word, before = chosen.length, after = 0, snapshot = accepted)
                // Moving the cursor away is how most people leave this word, so learn the choice now.
                confirmState(accepted) { confirmed ->
                    if (confirmed && field.learning && chosen != current.word.word)
                        words.chosen(word.swipe, word.context, word.recognition, chosen)
                }
            }
            Composition.None -> Unit
        }
    }

    private fun remember(swiped: Composition.Swiped) {
        val end = swiped.snapshot.selection.end
        if (!field.learning || end < 0 || swiped.snapshot.selected) return
        add(Remembered(end - swiped.tail.length, swiped.word, swiped.alternatives, swiped.swipe, swiped.context, swiped.recognition))
    }

    private fun add(word: Remembered) {
        remembered.removeAll { it.start < word.end && word.start < it.end }
        remembered += word
        if (remembered.size > MAX_REMEMBERED) remembered.removeAt(0)
    }

    /** Moves remembered words after an edit that replaced [start, end), and forgets the words it touched. */
    private fun shiftRemembered(start: Int, end: Int, change: Int) {
        if (remembered.isEmpty() || change == 0 && start == end) return
        val iterator = remembered.listIterator()
        while (iterator.hasNext()) {
            val word = iterator.next()
            when {
                word.end <= start -> Unit
                word.start >= end -> iterator.set(word.copy(start = word.start + change))
                else -> iterator.remove()
            }
        }
    }

    /** Shows the suggestions again when the cursor returns to a swiped word that is unchanged. */
    private fun recall(anchor: Snapshot): Boolean {
        val before = anchor.before ?: return false
        val after = anchor.after ?: return false
        if (before.isEmpty() && after.isEmpty()) {
            remembered.clear()
            return false
        }
        if (remembered.isEmpty()) return false
        val prefix = TypedPredictions.prefix(before)
        val suffix = after.takeWhile { it.isLetter() || it == '\'' || it == '’' }
        if (prefix.isEmpty() && suffix.isEmpty()) return false
        val start = anchor.selection.start - prefix.length
        val word = remembered.lastOrNull { it.start == start && it.word == prefix + suffix } ?: return false
        composition = Composition.Recalled(word, prefix.length, suffix.length, anchor)
        show(word.alternatives)
        return true
    }

    /** Only a swiped or typed word the user has not chosen themselves can change once the next word arrives. */
    private fun holdForNextWord(swiped: Composition.Swiped?, anchor: Snapshot) {
        val typed = typedWord
        typedWord = null
        if (typed != null && anchor == typed.snapshot && !anchor.selected) {
            awaitingNextWord = typed
            return
        }
        if (swiped == null) return
        val held = !swiped.chosen && swiped.trailingSpace && anchor == swiped.snapshot && !anchor.selected
            && anchor.knownSelection && anchor.before?.endsWith(swiped.inserted) == true
        awaitingNextWord = if (held) HeldWord(swiped.word, swiped.context, swiped.inserted, swiped.tail, swiped.snapshot, typed = false) else null
    }

    /** A word typed letter by letter, then a space. A typed apostrophe shows the spelling is meant, so it stays. */
    private fun holdTyped(before: String, respelled: Pair<String, String>?): HeldWord? {
        if (!autoPunctuation()) return null
        val typed = respelled?.first ?: before.takeLastWhile { it.isLetter() }
        val word = respelled?.second ?: typed
        if (word.isEmpty() || !word.all { it.isLetter() }) return null
        val context = before.dropLast(typed.length)
        if (context.lastOrNull()?.let { it.isLetterOrDigit() || it == '\'' || it == '’' } == true) return null
        val accepted = snapshot()
        if (!accepted.knownSelection || accepted.before?.endsWith("$word ") != true) return null
        return HeldWord(word, context, "$word ", "$word ", accepted, typed = true)
    }

    /** Once the next word is finished, its fit decides between look-alikes such as "we're" and "were". */
    private fun checkLookAlike() {
        val earlier = awaitingNextWord ?: return
        val anchor = snapshot()
        val before = anchor.before
        val followed = anchor.selection.end - earlier.snapshot.selection.end
        if (followed == 0 && !anchor.selected) return
        if (before == null || anchor.selected || followed !in 1..before.length) {
            awaitingNextWord = null
            return
        }
        val following = before.takeLast(followed)
        val next = following.trimEnd(' ', '.', ',', '!', '?', ';', ':')
        if (next == following) {
            if (TypedPredictions.prefix(next) != next) awaitingNextWord = null
            return
        }
        awaitingNextWord = null
        val start = earlier.snapshot.selection.end - earlier.tail.length
        if (next.isEmpty() || TypedPredictions.prefix(next) != next || start == lookAlikeChangedAt
            || !before.dropLast(followed).endsWith(earlier.inserted)) return
        val owner = identity
        words.lookAlike(earlier.context, earlier.word, next, earlier.typed) { replacement ->
            // Typing may carry on while the model decides, so the swap waits for a pause and checks the text then.
            if (replacement == null || !sameIdentity(owner, identity)) return@lookAlike
            swap = Swap(earlier, cased(replacement, earlier), start, earlier.tail + following, owner)
            applySwap()
        }
    }

    private fun applySwap() {
        val pending = swap ?: return
        if (hasPendingInput) return
        swap = null
        val anchor = snapshot()
        val before = anchor.before ?: return
        val reach = anchor.selection.end - pending.start
        if (!sameIdentity(pending.owner, identity) || anchor.selected || !anchor.knownSelection
            || reach !in pending.lead.length..minOf(before.length, pending.lead.length + MAX_SWAP_DELAY)) return
        val region = before.takeLast(reach)
        val earlier = pending.earlier
        if (!region.startsWith(pending.lead) || !(before.dropLast(reach) + earlier.tail).endsWith(earlier.inserted)) return
        replaceLookAlike(earlier, pending.replacement, region.drop(earlier.tail.length), pending.start, anchor)
    }

    private fun cased(replacement: String, original: HeldWord): String {
        val word = original.word
        if (word.length > 2 && word == word.uppercase() && word != word.lowercase()) return replacement.uppercase()
        val shifted = word.first().isUpperCase() && !word.lowercase().startsWith("i'")
        val sentenceCase = field.sentenceCapitalisation && autoCapitalise()
        return SwipeTyping.capitalise(replacement, original.context, 0, shifted, sentenceCase)
    }

    private fun replaceLookAlike(earlier: HeldWord, replacement: String, following: String, start: Int, anchor: Snapshot) {
        val showing = composition
        invalidate(false)
        val previous = remembered.lastOrNull { it.start == start && it.word == earlier.word }
        if (!edit(earlier.tail.length + following.length, "$replacement $following")) return
        previous?.let { add(it.copy(word = replacement)) }
        lookAlikeChangedAt = start
        val accepted = snapshot()
        if (automaticSpace == anchor) automaticSpace = accepted
        fullStop = fullStop?.let { if (it.snapshot == anchor) FullStop(it.time, accepted) else it }
        typedWord = typedWord?.let { if (it.snapshot == anchor) it.copy(snapshot = accepted) else null }
        if (showing is Composition.Swiped) {
            val context = accepted.before?.removeSuffix(showing.inserted) ?: showing.context
            val swiped = showing.copy(context = context, snapshot = accepted)
            composition = swiped
            remember(swiped)
        } else {
            composition = Composition.None
            show(emptyList())
            predictLater()
        }
        confirmState(accepted)
    }

    private fun edit(count: Int, text: String, after: Int = 0): Boolean {
        if (!sameIdentity(identity, editor.identity())) {
            reset()
            return false
        }
        val previous = selection
        val anchor = snapshot()
        val current = epoch
        val owner = identity
        val accepted = editor.replace(count, text, after)
        if (current != epoch || !sameIdentity(owner, editor.identity())) return false
        if (!accepted) {
            // A deletion may have succeeded before commitText failed. Never replay the transaction.
            remembered.clear()
            reset()
            return false
        }
        val actualBefore = editor.before()
        val actualAfter = editor.after()
        if (current != epoch || !sameIdentity(owner, editor.identity())) return false
        val requestedBefore = anchor.before?.let { (it.dropLast(count) + text).takeLast(Editor.CONTEXT_LENGTH) }
        val requestedAfter = anchor.after?.drop(after)
        val beforeMatches = requestedBefore == null || actualBefore == null ||
            if ((anchor.before?.length ?: 0) < Editor.CONTEXT_LENGTH || requestedBefore.length == Editor.CONTEXT_LENGTH)
                actualBefore == requestedBefore else actualBefore.endsWith(requestedBefore)
        val afterMatches = requestedAfter == null || actualAfter == null ||
            if ((anchor.after?.length ?: 0) < AFTER_CONTEXT_LENGTH) actualAfter == requestedAfter
            else actualAfter.startsWith(requestedAfter)
        if (!beforeMatches || !afterMatches) {
            // Filters may have accepted only part of the requested text. Retain the actual edit,
            // discard its evidence and dependent input, and recover the app's real cursor.
            remembered.clear()
            reset()
            selection = Selection(-1, -1)
            selectionConfirmed = false
            confirmState()
            return false
        }
        if (!anchor.available || actualBefore == null || actualAfter == null) {
            // Explicit typing still works in editors without readable context; do not invent a cursor.
            remembered.clear()
            selection = Selection(-1, -1)
            selectionConfirmed = false
            expectedSelections.clear()
            editSnapshot = snapshot()
            return true
        }
        if (previous.start >= 0 && previous.end >= 0) {
            val start = minOf(previous.start, previous.end)
            val end = maxOf(previous.start, previous.end)
            shiftRemembered(start - count, end + after, text.length - (end - start + count + after))
        } else remembered.clear()
        expectSelection(previous, count, text.length)
        editSnapshot = snapshot()
        return true
    }

    private fun expectSelection(previous: Selection, count: Int, inserted: Int) {
        if (previous.start >= 0 && previous.end >= 0) {
            val cursor = minOf(previous.start, previous.end) - count + inserted
            selection = Selection(cursor, cursor)
            selectionConfirmed = false
            if (expectedSelections.size == MAX_PENDING_INPUT) expectedSelections.removeFirst()
            expectedSelections += selection
        }
    }

    /** Submit one final choice for this occurrence, including the latest tentative alternative. */
    private fun leave() {
        val swiped = composition as? Composition.Swiped ?: return
        if (swiped.finalised) return
        swiped.finalised = true
        if (!field.learning || !selectionConfirmed || snapshot() != swiped.snapshot || !swiped.snapshot.available
            || swiped.snapshot.selected || swiped.snapshot.before?.endsWith(swiped.inserted) != true) return
        if (swiped.chosen) words.chosen(swiped.swipe, swiped.context, swiped.recognition, swiped.word)
        else words.kept(swiped.swipe, swiped.context, swiped.recognition, swiped.word)
    }

    private fun observe(text: String) {
        if (!field.learning || selection.start < 0 || selection.end < 0) {
            manualWord.clear()
            return
        }
        manualWord.edit(text, null, false)
    }

    /** Swipes and tap keys join the text with no space after some characters, so the word before them is not finished. */
    private fun startsNewWord(before: String) = SwipeTyping.prefix(before).isNotEmpty()

    private fun completeManualWord(anchor: Snapshot): String? {
        val continuesWord = anchor.after?.firstOrNull()?.let { it.isLetter() || it == '\'' || it == '’' } == true
        if (!field.learning || !anchor.knownSelection || anchor.selected
            || correcting != null && (anchor != editSnapshot || continuesWord)) {
            manualWord.clear()
            return null
        }
        val before = anchor.before
        return manualWord.edit(" ", before, before != null && before.length < Editor.CONTEXT_LENGTH)
    }

    /** A deleted space only waits; once a letter of the word goes, the word was a mistake. */
    private fun untypeIfDeleted(typed: Learned) {
        val after = editSnapshot ?: return
        if (!after.knownSelection || after.selected) return
        if (after.selection.end < typed.end) words.untyped(typed.word)
        else learned = Learned(typed.word, typed.end, after)
    }

    private fun typeWord(word: String, rememberCase: Boolean) {
        words.typed(word, rememberCase)
        learned = null
        val anchor = snapshot()
        val before = anchor.before ?: return
        if (!anchor.knownSelection || anchor.selected) return
        val index = before.lastIndexOf(word)
        if (index < 0) return
        val tail = before.substring(index + word.length)
        if (tail.length > 2 || tail.any { it.isLetterOrDigit() || it == '\'' || it == '’' }) return
        learned = Learned(word, anchor.selection.end - tail.length, anchor)
    }

    private fun learnWord(word: String?, correction: Composition.Swiped?, rememberCase: Boolean = true) {
        if (!field.learning || word.isNullOrEmpty()) return
        if (correction == null) typeWord(word, rememberCase)
        else if (!word.equals(correction.word, ignoreCase = true)) {
            words.corrected(correction.swipe, correction.context, correction.recognition, word)
            // The engine only counts a correction that resembles the swipe; the word was still typed.
            typeWord(word, rememberCase)
        } else if (correction.chosen) {
            words.chosen(correction.swipe, correction.context, correction.recognition, word)
        }
    }

    private fun predictLater() {
        val current = epoch
        later(PREDICTION_DELAY_MILLIS) { if (current == epoch) predict() }
    }

    private fun predict() {
        if (composition !is Composition.None || hasPendingInput) return
        val anchor = snapshot()
        // Predictions must belong to the last verified owned edit.
        if (editSnapshot != null && anchor != editSnapshot) return
        if (!anchor.available || !anchor.knownSelection) return
        val before = anchor.before ?: return
        val after = anchor.after ?: return
        // Browsers select the whole address when you tap it, and typing replaces it, so offer starters then too.
        if (field.starters && before.isEmpty() && after.isEmpty()) {
            val starters = STARTERS.filter(words::allowed)
            composition = Composition.Typed("", false, anchor, starters)
            show(starters)
            return
        }
        if (anchor.selected) return
        applySwap()
        if (snapshot() != anchor) return
        checkLookAlike()
        if (!field.suggestions) {
            show(emptyList())
            return
        }
        if (recall(anchor)) return
        if (after.firstOrNull()?.let { it.isLetter() || it == '\'' || it == '’' } == true) {
            show(emptyList())
            return
        }
        val lastWord = TypedPredictions.prefix(before.dropLast(1))
        val trailingSpace = before.endsWith(" ") && lastWord.isNotEmpty() && !words.known(lastWord)
        val context = if (trailingSpace) before.dropLast(1) else before
        val current = epoch
        completion?.set(true)
        val cancelled = AtomicBoolean(false)
        completion = cancelled
        completionSnapshot = anchor
        words.complete(context, cancelled, field.sentenceCapitalisation) { suggestions ->
            if (cancelled.get() || current != epoch || snapshot() != anchor) return@complete
            composition = Composition.Typed(TypedPredictions.prefix(context), trailingSpace, anchor, suggestions)
            show(suggestions)
        }
    }

    /**
     * The typed word at the end of [before] and the spelling a swipe gives it, or null if that is the same.
     * Each setting covers its own part: "london" needs auto-capitalisation, "dont" auto-punctuation, "im" both.
     */
    private fun respell(before: String): Pair<String, String>? {
        val word = before.takeLastWhile { it.isLetter() || it == '\'' || it == '’' }
        if (word.isEmpty()) return null
        val spelling = SwipeTyping.respell(word)
        if (spelling == word) return null
        val punctuated = spelling.count { !it.isLetter() } != word.count { !it.isLetter() }
        val cased = spelling.filter { it.isLetter() } != word.filter { it.isLetter() }
        if (punctuated && !autoPunctuation() || cased && !autoCapitalise()) return null
        return word to spelling
    }

    /** "i." followed by a space is the word "I", not the start of "i.e.". */
    private fun pronounBeforeFullStop(before: String): Pair<String, String>? {
        if (!before.endsWith("i.") || !autoCapitalise()) return null
        val previous = before.getOrNull(before.length - 3)
        if (previous != null && (previous.isLetterOrDigit() || previous in ".'’")) return null
        return "i." to "I."
    }

    private fun endsWord(text: String): Boolean {
        if (text.isEmpty()) return false
        val last = text.codePointBefore(text.length)
        return Character.isLetterOrDigit(last) || ")]}\"'’”".indexOf(last.toChar()) >= 0
    }

    companion object {
        private const val MAX_PENDING_INPUT = 128
        private const val MAX_PENDING_TEXT = 8192
        private const val MAX_TEXT_CHUNK = 256
        private const val DRAIN_BUDGET = 8
        private const val DRAIN_NANOS = 4_000_000L
        private const val DESTINATION_TIMEOUT_MILLIS = 500L
        private const val AFTER_CONTEXT_LENGTH = 32
        private const val FULL_STOP_MILLIS = 400L
        /** How many characters typed after the next word a late look-alike swap still allows. */
        private const val MAX_SWAP_DELAY = 64
        private const val PREDICTION_DELAY_MILLIS = 60L
        private val STARTERS = listOf("What", "How", "Where")
        private const val MAX_REMEMBERED = 1000
        /** Characters that end a word, so a swiped word right before one needs no space after it. */
        private const val WORD_CLOSERS = ".,!?;:)]}…"
    }
}
