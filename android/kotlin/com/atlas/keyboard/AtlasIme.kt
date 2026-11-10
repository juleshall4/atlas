package com.atlas.keyboard

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.hardware.display.DisplayManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.SurroundingText
import com.atlas.engine.Calibration
import com.atlas.engine.EmojiPages
import com.atlas.engine.Swipe
import com.atlas.engine.SwipeTyping
import com.atlas.engine.TypingSettings
import java.io.IOException
import kotlin.math.abs

/**
 * Atlas: swipe typing on the Titan 2 keyboard's touchpad, with a suggestion strip for the physical
 * keyboard. The keyboard window stays open while editing so the firmware can route touchpad events.
 */
class AtlasIme : InputMethodService() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var touchpad: Touchpad
    private lateinit var haptics: Haptics
    private lateinit var composer: Composer
    private lateinit var editor: ConnectionEditor
    private lateinit var overlay: TouchpadOverlay
    private val contact = Contact()
    private val cursor = CursorMode()
    /** The finger's path through cursor mode, drawn on the home screen's keyboard like a swipe. */
    private var cursorPath = ArrayList<Sample>()
    private var cursorControl = true
    private var swipeTyping = true
    /** Whether this cursor mode moved the cursor; only then does a click on Space select. */
    private var cursorMoved = false
    /** Space was clicked in cursor mode, so its repeats and release type nothing. */
    private var clickingSpace = false
    /**
     * Leaning moves nothing until then. The click on Space shifts the finger, and a finger that just landed
     * is still settling. Neither is a lean.
     */
    private var leanAfter = Long.MIN_VALUE
    /**
     * Select mode: a click on Space turned it on, and only the next press of Space turns it off. It stays on
     * when the finger lifts, so a later rest grows the same selection. Space types nothing while it is on.
     */
    private var selectMode = false
    /** The selection the app last reported, for apps that don't say where their text sits. */
    private var lastSelectionStart = -1
    /** The selection that leaning grows in select mode, once the click has selected the word. */
    private var selecting: Selection? = null
    /**
     * A selection that grows from its anchor, the word that the click on Space selected. It always ends at one
     * side of the anchor: it grows to the left or to the right of it, never to both sides.
     */
    private class Selection(val anchorStart: Int, val anchorEnd: Int) {
        var start = anchorStart
        var end = anchorEnd
        /** The end that moves, where the cursor goes when selecting stops. */
        val free get() = if (start < anchorStart) start else end
    }
    private val modifiers = Modifiers()
    private var strip: Strip? = null
    private var emoji: EmojiPages? = null
    private var field = Field.None
    private var editing = false
    private var typedInField = false
    private var fieldStartedAt = 0L
    private var passingBack = false
    private var inLauncher = false
    private val consumedKeys = HashSet<Int>()
    private var deadKeyPending = false
    private var tapKeys = ""
    private class PendingInput(var ready: Boolean, var action: (() -> Unit)?, val event: KeyEvent? = null,
                               val settlement: Contact.Settlement? = null)
    private val pendingInput = ArrayDeque<PendingInput>()
    private var inputGeneration = 0
    private var drainScheduled = false
    private val settlements = LinkedHashMap<Contact.Settlement, Runnable>()
    private val showStrip = Runnable {
        if (!editing || currentInputBinding == null) return@Runnable
        // Apps can drop a field yet still start input on it, so only a field that answers gets the strip.
        if (currentInputConnection?.getTextBeforeCursor(1, 0) == null) return@Runnable
        try {
            // A cancelled show request can leave the client waiting for a window surface.
            showWindow(true)
            requestShowSelf(0)
        } catch (error: RuntimeException) {
            // Titan firmware can drop its current client before Android reports the editor gone.
            Log.w("Atlas", "Could not show the suggestion strip", error)
        }
    }

    private val hideStrip = Runnable {
        if (editing) return@Runnable
        try {
            hideWindow()
            requestHideSelf(0)
        } catch (error: RuntimeException) {
            Log.w("Atlas", "Could not hide the suggestion strip", error)
        }
    }

    // Apps on Android's newer back handling ignore a back key from a keyboard, so a system back goes first.
    private val sendBack = Runnable { if (!BackService.back() && editing) sendDownUpKeyEvents(KeyEvent.KEYCODE_BACK) }

    private val startCursor = Runnable {
        // A key pressed while the finger rests ends the contact, so it cannot start cursor mode.
        if (!contact.canStartCursor || !cursor.rest(SystemClock.uptimeMillis())) return@Runnable
        cursorMoved = false
        // The contact now steers the cursor, so it must not also type when it lifts.
        contact.cancelActive()
        Trace.touch(cursorPath, false)
        haptics.click()
        stepCursor.reset()
        stepCursor.run()
    }

    /**
     * Checks the lean every frame so speed follows the finger at once. Leaning in moves one letter straight
     * away, then letters build up at the current speed.
     */
    private val stepCursor = object : Runnable {
        private var owed = 1f
        private var last = 0L

        override fun run() {
            if (!cursor.active) return
            val now = SystemClock.uptimeMillis()
            val direction = cursor.direction()
            if (clickingSpace || now < leanAfter) {
                // Once settled, a lean counts as fresh and steps at once, as when cursor mode starts.
                owed = 1f
                last = now
                main.postDelayed(this, FRAME_MILLIS)
                return
            }
            if (direction == 0) owed = 1f
            else if (last != 0L) owed += cursor.speed(now, words = selectMode) * (now - last) / 1000
            if (direction != 0 && owed >= 1f) {
                owed = (owed - 1f).coerceAtMost(1f)
                moveCursor(direction)
            }
            last = now
            main.postDelayed(this, FRAME_MILLIS)
        }

        fun reset() {
            owed = 1f
            last = 0L
        }

        /** Carries on after a slip, without the extra first step a fresh lean gets. */
        fun resume() {
            last = 0L
            main.removeCallbacks(this)
            run()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Calibration.init(this)
        haptics = Haptics(this)
        touchpad = Touchpad(getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
        editor = ConnectionEditor(::getCurrentInputConnection, ::getCurrentInputEditorInfo,
            { delay, action -> main.postDelayed(action, delay) })
        composer = Composer(editor, EngineWords(SwipeTyping.get(this)),
            { delay, action -> main.postDelayed(action, delay) }, ::clearSettlements,
            { TypingSettings.autoCapitalise(this) }, { TypingSettings.autoPunctuation(this) }) { strip?.words = it }
        // The firmware hands touchpad events to the topmost window titled "InputMethod", which is this
        // keyboard's window. They arrive at the window, not at any view, so catch them there.
        val window = window.window!!
        window.callback = TouchpadWindowCallback(window.callback) { event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) Log.d("AtlasDiag", "window down editing=$editing tp=${touchpad.accepts(event)} dev=${event.device?.name}")
            (editing && touchpad.accepts(event)).also { if (it) motion(event) }
        }
        overlay = TouchpadOverlay(this) { event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) Log.d("AtlasDiag", "overlay down editing=$editing tp=${touchpad.accepts(event)} dev=${event.device?.name}")
            (editing && touchpad.accepts(event)).also { if (it) motion(event) }
        }
        loadEmoji()
    }

    /** Reads the emoji list away from the main thread, which also loads the emoji font before Sym is first pressed. */
    private fun loadEmoji() {
        Thread {
            val recents = getSharedPreferences(EMOJI_PREFERENCES, MODE_PRIVATE).getString(RECENT_EMOJI, "").orEmpty().split(" ")
            val paint = Paint()
            val groups = try {
                assets.open("emoji.txt").reader().use { EmojiPages.parse(it, paint::hasGlyph) }
            } catch (error: IOException) {
                Log.w("Atlas", "Could not read the emoji list", error)
                return@Thread
            }
            main.post { emoji = EmojiPages(groups, recents).also { strip?.emoji = it } }
        }.start()
    }

    override fun onCreateInputView(): View = Strip(this, { index -> if (Haptics.enabled(this)) haptics.click(); enqueue { composer.choose(index) } },
        ::symbol, ::pickEmoji).also {
        strip = it
        it.emoji = emoji
        it.showSuggestions = showSuggestions()
        showModifiers()
    }

    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        Log.d("AtlasDiag", "startInput ${info.packageName} type=${info.inputType} restarting=$restarting")
        preserveDestinationInput()
        editor.cancelState()
        super.onStartInput(info, restarting)
        editing = info.inputType != EditorInfo.TYPE_NULL
        if (!restarting) {
            typedInField = false
            fieldStartedAt = SystemClock.uptimeMillis()
        }
        field = if (editing) Field.of(info.inputType, info.imeOptions) else Field.None
        inLauncher = editing && info.packageName == launcherPackage()
        contact.releaseKeys()
        clearSettlements()
        main.removeCallbacks(pickKeyboard)
        modifiers.clear()
        deadKeyPending = false
        lastSelectionStart = info.initialSelStart
        showModifiers()
        composer.liftOffMillis = TypingSettings.liftOffGraceMillis(this)
        tapKeys = TypingSettings.tapKeys(this)
        cursorControl = TypingSettings.cursorControl(this)
        swipeTyping = TypingSettings.swipeTyping(this)
        strip?.showSuggestions = showSuggestions()
        main.removeCallbacks(showStrip)
        main.removeCallbacks(hideStrip)
        if (editing) {
            composer.start(field, info.initialSelStart, info.initialSelEnd)
            main.post(showStrip)
            overlay.show()
        } else {
            composer.stop()
            overlay.hide()
            // A returning window starts with no field just before its own field takes focus. A strip that
            // hides for that moment tells the app the keyboard was closed, and the app then drops its field.
            main.postDelayed(hideStrip, NO_FIELD_HIDE_MILLIS)
        }
    }

    // Launchers like Niagara keep a search field focused on the home screen, where the bar only gets in the way.
    // Password, email and number fields get no suggestions, so the bar would only be an empty strip.
    private fun showSuggestions() = TypingSettings.suggestionBar(this) && !inLauncher && field.suggestions

    private fun launcherPackage(): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Back belongs to the app; the strip comes and goes with the text field instead.
        backDisposition = BACK_DISPOSITION_WILL_NOT_DISMISS
    }

    override fun onWindowShown() {
        super.onWindowShown()
        Log.d("AtlasDiag", "windowShown editing=$editing")
        // Android draws its hide and switch-keyboard buttons in this bar; the strip has no room for them.
        window.window?.insetsController?.hide(WindowInsets.Type.captionBar())
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        Log.d("AtlasDiag", "windowHidden editing=$editing")
        // A delayed hide from the previous app arrives just after this field starts. A later hide, or one
        // after you have typed, is the app's own, such as a tap outside the field or a Done key.
        main.removeCallbacks(showStrip)
        if (passingBack) {
            // Now that the strip is gone, the app's own back can run. If its field stays, so does the strip.
            passingBack = false
            main.post(sendBack)
            if (editing) main.postDelayed(showStrip, BACK_SETTLE_MILLIS)
        } else if (editing && !typedInField && SystemClock.uptimeMillis() - fieldStartedAt < STALE_HIDE_MILLIS) {
            main.post(showStrip)
        }
    }

    override fun onUnbindInput() {
        main.removeCallbacks(showStrip)
        main.removeCallbacks(sendBack)
        super.onUnbindInput()
    }

    override fun onFinishInput() {
        Log.d("AtlasDiag", "finishInput")
        preserveDestinationInput()
        // The old connection is still owned here; only a verified final explicit choice is learned.
        composer.stop(terminal = true)
        editor.cancelState()
        editing = false
        field = Field.None
        main.removeCallbacks(showStrip)
        clearSettlements()
        contact.cancel()
        endCursor()
        clickingSpace = false
        stopSelecting()
        deadKeyPending = false
        overlay.hide()
        super.onFinishInput()
    }

    override fun onEvaluateInputViewShown() = editing

    override fun onShowInputRequested(flags: Int, configChange: Boolean) = editing

    override fun onEvaluateFullscreenMode() = false

    override fun onUpdateSelection(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int, composingStart: Int, composingEnd: Int) {
        super.onUpdateSelection(oldStart, oldEnd, newStart, newEnd, composingStart, composingEnd)
        lastSelectionStart = newStart
        // Typing over the selection, deleting it, or a touch elsewhere in the text ends select mode.
        selecting?.let {
            val collapsed = newStart == newEnd
            val backAtEmptyAnchor = it.anchorStart == it.anchorEnd && newStart == it.anchorStart
            if (collapsed && oldStart != oldEnd && !backAtEmptyAnchor
                || !collapsed && (minOf(newStart, newEnd) > it.anchorStart || maxOf(newStart, newEnd) < it.anchorEnd)) stopSelecting()
        }
        Log.d("AtlasDiag", "selection $oldStart->$newStart composing=$composingStart..$composingEnd")
        if (editing && composer.selectionChanged(newStart, newEnd)) clearSettlements()
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        super.onUpdateCursorAnchorInfo(info)
        editor.cursorAnchor(info)
    }

    /** While the strip is hidden, the touchpad reaches the keyboard through the focused app. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) Log.d("AtlasDiag", "generic down editing=$editing tp=${touchpad.accepts(event)}")
        if (!editing || !touchpad.accepts(event)) return super.onGenericMotionEvent(event)
        motion(event)
        return true
    }

    private fun motion(event: MotionEvent) {
        if (touchpad.action(event) != MotionEvent.ACTION_MOVE) Log.d("AtlasDiag", "motion ${touchpad.action(event)} words=${field.words} cursor=${cursor.active}")
        if (!field.words) return
        typedInField = true
        val samples = touchpad.samples(event)
        when (touchpad.action(event)) {
            MotionEvent.ACTION_DOWN -> {
                val first = samples.firstOrNull() ?: Sample(Float.NaN, Float.NaN, event.eventTime)
                haptics.begin(samples.firstOrNull())
                contact.down(first)
                if (cursor.resume(first)) {
                    // Back within the slip recovery period: still steering the cursor, never typing.
                    main.removeCallbacks(endSlippedCursor)
                    contact.cancelActive()
                    cursor.move(samples.drop(1))
                    traceCursor(samples, ended = false)
                    stepCursor.resume()
                    return
                }
                endCursor()
                // Select mode is sticky: a finger on Space grows the selection at once, with no rest.
                if (selectMode && cursorControl && !stripPaging && cursor.hold(first)) {
                    contact.cancelActive()
                    cursor.move(samples.drop(1))
                    cursorPath = ArrayList(samples.filter { it.valid })
                    Trace.touch(cursorPath, false)
                    cursorMoved = false
                    leanAfter = first.time + LAND_SETTLE_MILLIS
                    stepCursor.reset()
                    stepCursor.run()
                    return
                }
                contact.move(samples.drop(1))
                // Alt and Sym turn swipes into strip pages, so the cursor waits until they are off.
                // Cursor mode waits until typing ends. The finger that pushed Space cannot start it until it lifts.
                if (cursorControl && !stripPaging && contact.canStartCursor && cursor.down(first)) {
                    cursor.move(samples.drop(1))
                    cursorPath = ArrayList(samples.filter { it.valid })
                    main.postAtTime(startCursor, maxOf(first.time + CursorMode.REST_MILLIS, contact.typingUntil))
                }
                Trace.touch(contact.points, false)
            }
            MotionEvent.ACTION_MOVE -> {
                cursor.move(samples)
                if (cursor.active) {
                    traceCursor(samples, ended = false)
                    return
                }
                cursorPath.addAll(samples.filter { it.valid })
                contact.move(samples)
                val travel = contact.travel
                val first = contact.points.firstOrNull()
                // Wait until a configured tap key's contact is a swipe, so short taps pulse only on acceptance.
                if (swipeTyping && !stripPaging && travel != null && first != null && (travel >= Contact.SWIPE_KEYS
                    || !isTapKey(Layout.keyAt(first.x, first.y)))) {
                    // While you type, the swipe stays quiet. It starts to vibrate when typing ends.
                    haptics.move(samples.filter { it.time >= contact.typingUntil })
                }
                Trace.touch(contact.points, false)
            }
            MotionEvent.ACTION_UP -> {
                if (cursor.active) {
                    main.removeCallbacks(stepCursor)
                    cursor.lift()
                    // Through a slip the path stays open, so a finger that comes back carries on drawing it.
                    traceCursor(samples, ended = false)
                    // A click on Space lifts the finger off the touchpad. While Space is down, cursor mode waits.
                    if (!clickingSpace) main.postDelayed(endSlippedCursor, liftGraceMillis)
                    return
                }
                endCursor()
                val points = contact.points
                contact.up(samples, SystemClock.uptimeMillis())?.let { pending ->
                    if (settlements.size == Contact.MAX_PENDING_CONTACTS || !makeRoom()) {
                        contact.settle(pending, pending.deadline)
                        return@let
                    }
                    val input = PendingInput(false, null, settlement = pending)
                    pendingInput.addLast(input)
                    val settle = Runnable {
                        settlements.remove(pending)
                        val result = contact.settle(pending, SystemClock.uptimeMillis())
                        input.action = { result?.let(::outcome) }
                        input.ready = true
                        drainInput()
                    }
                    settlements[pending] = settle
                    main.postAtTime(settle, pending.deadline)
                }
                Trace.touch(if (points.size <= Swipe.MAX_POINTS && samples.all { it.valid }) points else emptyList(), true)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                contact.cancelActive()
                endCursor()
                Trace.touch(emptyList(), true)
            }
        }
    }

    private val endSlippedCursor = Runnable { endCursor() }

    private fun traceCursor(samples: List<Sample>, ended: Boolean) {
        val added = samples.filter { it.valid }
        // The preview draws at most a swipe's worth of points; a long session starts a fresh path.
        if (cursorPath.size + added.size > Swipe.MAX_POINTS) cursorPath = ArrayList(listOfNotNull(cursorPath.lastOrNull()))
        cursorPath.addAll(added)
        Trace.touch(cursorPath, ended)
    }

    private fun endCursor() {
        if (cursor.active || cursor.lifted) Trace.touch(cursorPath, true)
        main.removeCallbacks(endSlippedCursor)
        main.removeCallbacks(startCursor)
        main.removeCallbacks(stepCursor)
        cursor.end()
    }

    private fun stopSelecting() {
        selectMode = false
        selecting = null
    }

    /**
     * How long cursor mode waits for a finger that lifted. A click on Space lifts the finger before or after
     * the key goes down, so cursor mode always waits at least long enough for the click.
     */
    private val liftGraceMillis get() = maxOf(composer.liftOffMillis.toLong(), CLICK_GRACE_MILLIS)

    /** Sends one arrow key press, or grows the selection by a word, in order with the rest of the typing. */
    private fun moveCursor(direction: Int) {
        if (direction == 0) return
        cursorMoved = true
        val time = SystemClock.uptimeMillis()
        val code = if (direction > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        val arrow = KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, InputDevice.SOURCE_KEYBOARD)
        enqueue {
            // The click that starts selecting may still wait in the queue, so decide here.
            selecting?.let { grow(it, direction); return@enqueue }
            // A cursor at the start or end of the text cannot move, so it stays quiet. An app that does not
            // answer still gets the pulse.
            val connection = currentInputConnection
            val next = if (direction > 0) connection?.getTextAfterCursor(1, 0) else connection?.getTextBeforeCursor(1, 0)
            if (next?.isEmpty() != true && Haptics.enabled(this)) haptics.pulse()
            command(arrow)
        }
    }

    /**
     * Moves the free end of the selection by a whole word. Leaning away from the anchor grows the selection to
     * the far side of the next word. Leaning back shrinks it to the near side of the next word in, and never
     * past the anchor. At the anchor, the lean picks the side.
     */
    private fun grow(selection: Selection, direction: Int) {
        val connection = currentInputConnection ?: return
        val movingStart = when {
            selection.start < selection.anchorStart -> true
            selection.end > selection.anchorEnd -> false
            else -> direction < 0
        }
        val passed = when {
            movingStart && direction < 0 -> connection.getTextBeforeCursor(WORD_REACH, 0)?.let { wordAtEnd(it) }
            !movingStart && direction > 0 -> connection.getTextAfterCursor(WORD_REACH, 0)?.let { wordAtStart(it) }
            movingStart -> connection.getSelectedText(0)?.let { wordAtStart(it, wordFirst = true) }
            else -> connection.getSelectedText(0)?.let { wordAtEnd(it, wordFirst = true) }
        } ?: 0
        val start = if (movingStart) (selection.start + direction * passed).coerceAtMost(selection.anchorStart) else selection.start
        val end = if (movingStart) selection.end else (selection.end + direction * passed).coerceAtLeast(selection.anchorEnd)
        // The selection stops at the ends of the text.
        if (passed == 0 || start < 0 || (start == selection.start && end == selection.end)) return
        composer.command(destructive = false, fieldLocal = true) { connection.setSelection(start, end) }
        selection.start = start
        selection.end = end
        if (Haptics.enabled(this)) haptics.pulse()
    }

    /**
     * How many characters from the start of [text] make up the gap and then the word after it. With
     * [wordFirst], the word comes first and then the gap, so the end lands at the start of the next word.
     */
    private fun wordAtStart(text: CharSequence, wordFirst: Boolean = false): Int {
        var at = 0
        for (inWord in if (wordFirst) listOf(true, false) else listOf(false, true))
            while (at < text.length && isWordCharacter(text[at]) == inWord) at++
        return at
    }

    /** As [wordAtStart], from the end of [text] back. */
    private fun wordAtEnd(text: CharSequence, wordFirst: Boolean = false): Int {
        var at = text.length
        for (inWord in if (wordFirst) listOf(true, false) else listOf(false, true))
            while (at > 0 && isWordCharacter(text[at - 1]) == inWord) at--
        return text.length - at
    }

    /**
     * Whether this Space press turns select mode on or off. In select mode, every press does. Otherwise only a
     * press in cursor mode does, after the cursor moved: a cursor mode that did not move it was not wanted, so
     * Space types a space. Pressing Space presses the finger into the touchpad, which lifts it, so either may
     * arrive first.
     */
    private fun clicksSelectMode(event: KeyEvent) = event.keyCode == KeyEvent.KEYCODE_SPACE && event.repeatCount == 0
        && (selectMode || (cursor.active || cursor.lifted) && cursorMoved)

    /** Turns select mode on at the word at the cursor, or off with the cursor at the free end. */
    private fun clickSpace() {
        if (Haptics.enabled(this)) haptics.click()
        if (!selectMode) {
            selectMode = true
            enqueue { selectWord() }
            return
        }
        val selection = selecting
        stopSelecting()
        if (selection != null) enqueue {
            composer.command(destructive = false, fieldLocal = true) {
                currentInputConnection?.setSelection(selection.free, selection.free) ?: false
            }
        }
    }

    /**
     * Selects the word at the cursor, which becomes the anchor that leaning grows from. Between words, the
     * anchor is the cursor itself, and the first lean selects the next word on that side.
     */
    private fun selectWord() {
        composer.command(destructive = false, fieldLocal = true) {
            val connection = currentInputConnection ?: return@command false.also { stopSelecting() }
            connection.finishComposingText()
            val around = connection.getSurroundingText(WORD_REACH, WORD_REACH, 0) ?: return@command false.also { stopSelecting() }
            val text = around.text
            var start = around.selectionStart
            var end = around.selectionEnd
            while (start > 0 && isWordCharacter(text[start - 1])) start--
            while (end < text.length && isWordCharacter(text[end])) end++
            val offset = fieldOffset(connection, around) ?: return@command false.also { stopSelecting() }
            selecting = Selection(offset + start, offset + end)
            start == end || connection.setSelection(offset + start, offset + end)
        }
    }

    /**
     * Where [around] starts in the field. Apps that don't supply it, such as many built with Jetpack
     * Compose, report -1; used as is, that shifts the selection a character to the left. The full text
     * then gives the cursor's place, and failing that, the app's last reported selection.
     */
    private fun fieldOffset(connection: InputConnection, around: SurroundingText): Int? {
        if (around.offset >= 0) return around.offset
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
        val cursor = if (extracted != null && extracted.startOffset >= 0 && extracted.selectionStart >= 0)
            extracted.startOffset + extracted.selectionStart else lastSelectionStart
        return if (cursor >= around.selectionStart) cursor - around.selectionStart else null
    }

    private fun isWordCharacter(character: Char) = Character.isLetterOrDigit(character) || character == '\'' || character == '’'

    private fun symbol(symbol: String) {
        enqueue {
            composer.symbol(symbol)
            modifiers.consume()
            showModifiers()
        }
    }

    private fun pickEmoji(picked: String) {
        enqueue {
            composer.symbol(picked)
            emoji?.use(picked)?.let { recents ->
                getSharedPreferences(EMOJI_PREFERENCES, MODE_PRIVATE).edit().putString(RECENT_EMOJI, recents.joinToString(" ")).apply()
            }
            modifiers.consume()
            showModifiers()
        }
    }

    private val stripPaging get() = modifiers.alt != Modifiers.Level.Off || modifiers.sym != Modifiers.Level.Off

    private fun clearSettlements() {
        inputGeneration++
        drainScheduled = false
        settlements.values.forEach(main::removeCallbacks)
        settlements.clear()
        val releases = pendingInput.filter { it.event?.action == KeyEvent.ACTION_UP }
        pendingInput.clear()
        contact.cancel()
        // Discarded typing must still release physically held modifiers.
        releases.forEach { it.action?.invoke() }
    }

    /** Move only an interpretable ready key prefix into the existing navigation barrier. */
    private fun preserveDestinationInput() {
        if (!composer.awaitingDestination || deadKeyPending) return
        repeat(MAX_PENDING_INPUT + MODIFIER_RELEASE_SLOTS) {
            val pending = pendingInput.firstOrNull() ?: return
            if (!pending.ready || pending.settlement != null) return
            val event = pending.event ?: return
            when {
                event.action == KeyEvent.ACTION_UP -> {
                    if (Modifiers.isModifier(event.keyCode)) modifiers.key(event)
                }
                Modifiers.isModifier(event.keyCode) -> modifiers.key(event)
                event.isCtrlPressed || event.isMetaPressed || KeyEvent.isModifierKey(event.keyCode)
                    || altShortcut(event) != null -> return
                event.keyCode == KeyEvent.KEYCODE_SPACE -> {
                    composer.space(event.repeatCount > 0, event.eventTime)
                    modifiers.useHeld()
                }
                else -> {
                    val typed = event.getUnicodeChar(event.metaState or modifiers.metaState)
                    if (typed <= 0 || !Character.isValidCodePoint(typed) || Character.isISOControl(typed)) return
                    composer.type(String(Character.toChars(typed)), shifted = modifiers.shift == Modifiers.Level.Once)
                    modifiers.consume()
                }
            }
            pendingInput.removeFirst()
        }
    }

    private fun makeRoom(): Boolean {
        while (pendingInput.size >= MAX_PENDING_INPUT) {
            val surplus = pendingInput.firstOrNull { it.settlement != null } ?: return false
            pendingInput.remove(surplus)
            surplus.settlement?.let {
                settlements.remove(it)?.let(main::removeCallbacks)
                contact.settle(it, it.deadline)
            }
        }
        return true
    }

    private fun enqueue(event: KeyEvent? = null, action: () -> Unit) {
        // Retain accepted keys under overload; shed a contact first, otherwise refuse only the new item.
        if (!makeRoom()) {
            if (event?.action != KeyEvent.ACTION_UP || !Modifiers.isModifier(event.keyCode)) return
            val previous = pendingInput.lastOrNull { it.event?.keyCode == event.keyCode }?.event
            val held = if (previous == null) modifiers.isHeld(event.keyCode) else previous.action == KeyEvent.ACTION_DOWN
            // Reserve one ordered release for each physical Shift/Alt key, never ahead of its down.
            if (!held || pendingInput.size >= MAX_PENDING_INPUT + MODIFIER_RELEASE_SLOTS) return
        }
        pendingInput.addLast(PendingInput(true, action, event))
        drainInput()
    }

    private fun drainInput() {
        if (drainScheduled) return
        val started = System.nanoTime()
        var actions = 0
        while (pendingInput.firstOrNull()?.ready == true) {
            if (actions++ == DRAIN_BUDGET || System.nanoTime() - started >= DRAIN_NANOS) {
                drainScheduled = true
                val generation = inputGeneration
                main.post {
                    if (generation != inputGeneration) return@post
                    drainScheduled = false
                    drainInput()
                }
                return
            }
            pendingInput.removeFirst().action?.invoke()
        }
    }

    private fun outcome(outcome: Contact.Outcome) {
        when (outcome) {
            is Contact.Outcome.Swipe -> {
                Log.d("AtlasDiag", "outcome swipe paging=$stripPaging swipeTyping=$swipeTyping")
                if (stripPaging) {
                    // Sideways swipes page through the symbols or emoji on the strip. The pages follow the finger.
                    val dx = (outcome.points.last().x - outcome.points.first().x) * 10
                    val dy = (outcome.points.last().y - outcome.points.first().y) * 4
                    if (modifiers.sym != Modifiers.Level.Off && abs(dy) > abs(dx) && abs(dy) >= Contact.PAGE_KEYS) {
                        // Up and down swipes, measured in key rows, jump between emoji groups.
                        strip?.jump(if (dy < 0) 1 else -1)
                        haptics.pulse(HapticPreset.Strong)
                    } else if (abs(dx) >= Contact.PAGE_KEYS) {
                        strip?.turn(if (dx > 0) -1 else 1)
                        haptics.pulse(HapticPreset.Strong)
                    }
                    return
                }
                if (!swipeTyping) return
                composer.swipe(letterSpace(outcome.points), outcome.startedAt, outcome.endedAt, modifiers.metaState)
                modifiers.consume()
            }
            is Contact.Outcome.Tap -> {
                if (!isTapKey(outcome.key)) return
                // In select mode, only a press of Space acts. A tap must not type over the selection.
                if (outcome.key == Layout.SPACE && selectMode) return
                val key = if (outcome.key == Layout.SPACE) " " else outcome.key
                haptics.tap()
                if (key == " ") {
                    composer.space(repeated = false, time = outcome.endedAt)
                } else {
                    val word = if ((key == "I" && TypingSettings.autoCapitalise(this)) || modifiers.shift != Modifiers.Level.Off) key else key.lowercase()
                    composer.type(word, letterTap = true, shifted = modifiers.shift == Modifiers.Level.Once)
                }
                modifiers.consume()
            }
        }
        showModifiers()
    }

    private fun isTapKey(key: String): Boolean {
        if (stripPaging) return false
        return if (key == Layout.SPACE) ' ' in tapKeys else Layout.isLetter(key) && key[0] in tapKeys
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (KeyCapture.waiting?.invoke(event) == true) return true
        // Android hands back to a shown keyboard first, which would only close the strip.
        if (keyCode == KeyEvent.KEYCODE_BACK && editing && isInputViewShown) return true
        if (!editing || keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        typedInField = true
        if (touchpad.isGestureKey(event)) return true
        // Holding Sym opens Android's keyboard list. Any other key first means Sym is only a modifier.
        if (keyCode != KeyEvent.KEYCODE_SYM) main.removeCallbacks(pickKeyboard)
        else if (event.repeatCount == 0) main.postDelayed(pickKeyboard, ViewConfiguration.getLongPressTimeout().toLong())
        contact.key(keyCode, true, event.eventTime)
        if (keyCode == KeyEvent.KEYCODE_SPACE && clickingSpace) return true
        if (clicksSelectMode(event)) {
            // Cursor mode carries on through the click, for the finger that stays on Space.
            main.removeCallbacks(endSlippedCursor)
            clickingSpace = true
            consumedKeys += keyCode
            clickSpace()
            return true
        }
        // Any other key ends select mode. The selection stays, to type over, delete or copy.
        if (selectMode && !Modifiers.isModifier(keyCode) && keyCode != KeyEvent.KEYCODE_SPACE) stopSelecting()
        // Any other key press means the finger was pressing, not resting on the touchpad.
        endCursor()
        if (press(event)) {
            consumedKeys += keyCode
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Types or forwards a key, after any input still waiting. */
    private fun press(event: KeyEvent): Boolean {
        if (pendingInput.isNotEmpty()) {
            val captured = KeyEvent(event)
            enqueue(captured) {
                key(captured)
                showModifiers()
            }
            return true
        }
        val handled = key(event)
        showModifiers()
        return handled
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (KeyCapture.waiting?.invoke(event) == true) return true
        if (keyCode == KeyEvent.KEYCODE_BACK && editing && isInputViewShown) {
            passingBack = true
            requestHideSelf(0)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_SYM) main.removeCallbacks(pickKeyboard)
        if (!editing || keyCode == KeyEvent.KEYCODE_BACK) {
            return consumedKeys.remove(keyCode) || super.onKeyUp(keyCode, event)
        }
        if (touchpad.isGestureKey(event)) return true
        contact.key(keyCode, false, event.eventTime)
        if (keyCode == KeyEvent.KEYCODE_SPACE && clickingSpace) {
            clickingSpace = false
            leanAfter = SystemClock.uptimeMillis() + CLICK_SETTLE_MILLIS
            // The finger came off with the click. If it does not come back to Space soon, cursor mode ends.
            if (cursor.lifted) main.postDelayed(endSlippedCursor, liftGraceMillis)
            consumedKeys.remove(keyCode)
            return true
        }
        if (pendingInput.isNotEmpty()) {
            val captured = KeyEvent(event)
            consumedKeys.remove(keyCode)
            enqueue(captured) {
                if (Modifiers.isModifier(keyCode)) modifiers.key(captured)
                showModifiers()
            }
            return true
        }
        if (Modifiers.isModifier(keyCode)) {
            modifiers.key(event)
            showModifiers()
        }
        return consumedKeys.remove(keyCode) || super.onKeyUp(keyCode, event)
    }

    /** Captures each app-handled key as a paired command in the composer's ordered input queue. */
    private fun key(event: KeyEvent): Boolean {
        if (Modifiers.isModifier(event.keyCode)) {
            modifiers.key(event)
            return true
        }
        // A lone Ctrl or Meta press is not a shortcut. Each shortcut key carries its meta state.
        if (KeyEvent.isModifierKey(event.keyCode)) {
            modifiers.useHeld()
            return true
        }
        altShortcut(event)?.let {
            modifiers.useHeld()
            command(it, meta = it.metaState)
            return true
        }
        if (event.isCtrlPressed && event.keyCode != KeyEvent.KEYCODE_DEL || event.isMetaPressed) {
            modifiers.useHeld()
            command(event)
            return true
        }
        if (deadKeyPending && (event.keyCode == KeyEvent.KEYCODE_SPACE || event.keyCode == KeyEvent.KEYCODE_DEL)) {
            command(event, textKey = true)
            if (event.keyCode == KeyEvent.KEYCODE_SPACE) modifiers.consume() else modifiers.useHeld()
            deadKeyPending = false
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                // Alt with Backspace deletes the previous word. Locked Alt is for numbers, so Backspace deletes one character.
                val alt = modifiers.alt == Modifiers.Level.Once
                composer.backspace(alt)
                if (alt) modifiers.consume() else modifiers.useHeld()
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { composer.enter(); modifiers.useHeld() }
            KeyEvent.KEYCODE_SPACE -> { composer.space(event.repeatCount > 0, event.eventTime); modifiers.useHeld() }
            KeyEvent.KEYCODE_TAB -> { modifiers.useHeld(); command(event) }
            else -> {
                val typed = event.getUnicodeChar(event.metaState or modifiers.metaState)
                val dead = typed and KeyCharacterMap.COMBINING_ACCENT != 0
                if (dead || deadKeyPending || typed <= 0 || !Character.isValidCodePoint(typed) || Character.isISOControl(typed)) {
                    val textKey = dead || typed > 0 && Character.isValidCodePoint(typed) && !Character.isISOControl(typed)
                    command(event, textKey = textKey)
                    if (textKey) modifiers.consume() else modifiers.useHeld()
                    if (dead || typed > 0) deadKeyPending = dead
                    return true
                }
                val text = String(Character.toChars(typed))
                val shifted = modifiers.shift == Modifiers.Level.Once
                modifiers.consume()
                // Controlled insertion avoids unacknowledged app key updates overtaking later input.
                composer.type(text, shifted = shifted)
            }
        }
        return true
    }

    /** The keyboard has no Ctrl key, so holding Alt with a shortcut letter sends Ctrl with it. */
    private fun altShortcut(event: KeyEvent): KeyEvent? {
        if (!modifiers.altHeld || event.isCtrlPressed || event.isMetaPressed) return null
        val (code, meta) = when (event.keyCode) {
            // Ctrl+Shift+Z is the redo that Android, Compose and web text fields all accept.
            KeyEvent.KEYCODE_Y -> KeyEvent.KEYCODE_Z to (KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
            in FIELD_SHORTCUTS -> event.keyCode to KeyEvent.META_CTRL_ON
            else -> return null
        }
        return KeyEvent(event.downTime, event.eventTime, event.action, code, event.repeatCount,
            KeyEvent.normalizeMetaState(meta), event.deviceId, if (code == event.keyCode) event.scanCode else 0,
            event.flags, event.source)
    }

    private fun command(event: KeyEvent, textKey: Boolean = false,
        meta: Int = KeyEvent.normalizeMetaState(event.metaState or modifiers.metaState)) {
        val down = KeyEvent(event.downTime, event.eventTime, KeyEvent.ACTION_DOWN, event.keyCode,
            event.repeatCount, meta, event.deviceId, event.scanCode, event.flags, event.source)
        val up = KeyEvent(event.downTime, event.eventTime, KeyEvent.ACTION_UP, event.keyCode,
            0, meta, event.deviceId, event.scanCode, event.flags, event.source)
        val navigation = event.keyCode in NAVIGATION_KEYS
        val shortcut = event.isCtrlPressed && !event.isMetaPressed && event.keyCode in FIELD_SHORTCUTS
        val destructive = textKey || event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL
            || event.keyCode in DESTRUCTIVE_SHORTCUTS || !navigation && !shortcut
        composer.command(destructive, textKey || navigation || shortcut || event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            val connection = currentInputConnection
            if (connection == null) false else try {
                var released = false
                val sent = try {
                    connection.sendKeyEvent(down)
                } finally {
                    // Always release a dispatched key, including when the down result is ambiguous.
                    released = connection.sendKeyEvent(up)
                }
                sent && released
            } catch (error: RuntimeException) {
                Log.w("Atlas", "Could not forward an ordered key", error)
                false
            }
        }
    }

    private val pickKeyboard = Runnable {
        // The picker can take the Sym release, so Sym must not stay held.
        modifiers.cancel(KeyEvent.KEYCODE_SYM)
        showModifiers()
        if (Haptics.enabled(this)) haptics.click()
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    private fun showModifiers() {
        strip?.showSymbols = modifiers.alt != Modifiers.Level.Off
        strip?.showEmoji = modifiers.sym != Modifiers.Level.Off
        contact.paging = stripPaging
    }

    /** Converts touchpad points to the recogniser's letter space. */
    private fun letterSpace(points: List<Sample>): Swipe {
        val start = points.first().time
        return Swipe(
            FloatArray(points.size) { points[it].x },
            FloatArray(points.size) { points[it].y * 4 / 3 },
            FloatArray(points.size) { (points[it].time - start).toFloat() },
        )
    }

    override fun onDestroy() {
        editing = false
        main.removeCallbacksAndMessages(null)
        settlements.clear()
        pendingInput.clear()
        contact.releaseKeys()
        composer.stop()
        editor.cancelState()
        overlay.hide()
        super.onDestroy()
    }

    companion object {
        private const val MAX_PENDING_INPUT = 128
        private const val EMOJI_PREFERENCES = "emoji"
        private const val RECENT_EMOJI = "recent"
        private const val BACK_SETTLE_MILLIS = 300L
        /** Delayed hides from the previous app arrived within 25 ms of the new field starting. */
        private const val STALE_HIDE_MILLIS = 500L
        /** The field of a returning window started within 30 ms of its empty start. */
        private const val NO_FIELD_HIDE_MILLIS = 200L
        /** How long cursor mode waits for a finger lifted by a click on Space, before and after the key. */
        private const val CLICK_GRACE_MILLIS = 250L
        /** How long after a click on Space comes up the finger may still be settling, so leaning waits. */
        private const val CLICK_SETTLE_MILLIS = 150L
        /** How long a finger that lands on Space in select mode settles before leaning grows the selection. */
        private const val LAND_SETTLE_MILLIS = 80L
        private const val FRAME_MILLIS = 16L
        /** Characters read on each side of the cursor to find the word it is in. */
        private const val WORD_REACH = 64
        /** One for each physical Shift, Alt and Sym key. */
        private const val MODIFIER_RELEASE_SLOTS = 5
        private const val DRAIN_BUDGET = 8
        private const val DRAIN_NANOS = 4_000_000L
        private val NAVIGATION_KEYS = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN)
        private val FIELD_SHORTCUTS = setOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_X,
            KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Y)
        private val DESTRUCTIVE_SHORTCUTS = setOf(KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_V,
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Y)
    }
}
