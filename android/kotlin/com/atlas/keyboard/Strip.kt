package com.atlas.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import com.atlas.engine.EmojiPages

/**
 * The bar above the keyboard: word suggestions, or pages of extra symbols while Alt is on, or emoji while Sym is on.
 * An emoji search shows its query on the left, with the matches after it.
 */
class Strip(
    context: Context,
    private val choose: (Int) -> Unit,
    private val symbol: (String) -> Unit,
    private val pickEmoji: (String) -> Unit,
) : LinearLayout(context) {
    private val typeface = resources.getFont(R.font.inter_regular)
    private val symbols = Keys.extraSymbols()
    private val wordViews = WORD_ORDER.map { index -> item(17f) { choose(index) } }
    private val symbolViews = List(SYMBOLS_PER_PAGE) { item(20f) { view ->
        if (showEmoji) pickEmoji(view.text.toString()) else symbol(view.text.toString())
    } }
    private val queryView = item(17f) {}.apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        ellipsize = TextUtils.TruncateAt.START
        isClickable = false
    }
    private val emptyView = item(17f) {}.apply {
        text = "No emoji"
        setTextColor(MUTED)
        isClickable = false
    }
    private var page = 0
    private var navigationInset = 0

    var showSuggestions = true
        set(value) {
            if (field == value) return
            field = value
            render()
        }

    var words: List<String> = emptyList()
        set(value) {
            field = value
            render()
        }

    var showSymbols = false
        set(value) {
            if (field != value) page = 0
            field = value
            render()
        }

    /** Null until the emoji list has loaded. */
    var emoji: EmojiPages? = null
        set(value) {
            field = value
            render()
        }

    var showEmoji = false
        set(value) {
            if (field == value) return
            if (value) emoji?.reset()
            field = value
            render()
        }

    private val showPages get() = showSymbols || showEmoji

    init {
        for (view in wordViews) addView(view, LayoutParams(0, dp(56), 1f))
        addView(queryView, LayoutParams(0, dp(56), (EmojiPages.PER_PAGE - EmojiPages.RESULTS_PER_PAGE).toFloat()))
        addView(emptyView, LayoutParams(0, dp(56), EmojiPages.RESULTS_PER_PAGE.toFloat()))
        for (view in symbolViews) addView(view, LayoutParams(0, dp(56), 1f))
        // Screen readers go left to right, so point them at the best word first.
        val ranked = wordViews.withIndex().sortedBy { WORD_ORDER[it.index] }.map { it.value }
        for (view in ranked) view.id = generateViewId()
        ranked.zipWithNext { view, next -> view.accessibilityTraversalBefore = next.id }
        // Keep the words clear of the gesture handle, which draws over the bottom of the keyboard.
        setOnApplyWindowInsetsListener { _, insets ->
            navigationInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            render()
            insets
        }
        render()
    }

    /** Moves through the symbol or emoji pages, wrapping at either end. */
    fun turn(direction: Int) {
        if (showEmoji) {
            emoji?.turn(direction)
        } else {
            val pages = (symbols.size + SYMBOLS_PER_PAGE - 1) / SYMBOLS_PER_PAGE
            page = Math.floorMod(page + direction, pages)
        }
        render()
    }

    /** Shows the emoji search as it is now. */
    fun refresh() = render()

    /** Moves to the next or previous emoji group. */
    fun jump(direction: Int) {
        emoji?.jump(direction)
        render()
    }

    private fun item(size: Float, press: (TextView) -> Unit) = TextView(context).apply {
        typeface = this@Strip.typeface
        textSize = size
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        isFocusable = false
        setPadding(dp(6), 0, dp(6), 0)
        setOnClickListener { press(this) }
    }

    private fun render() {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val ink = if (dark) Color.WHITE else Color.BLACK
        val visible = showSuggestions || showPages
        setBackgroundColor(if (!visible) Color.TRANSPARENT else if (dark) Color.BLACK else Color.WHITE)
        setPadding(dp(8), 0, dp(8), if (visible) navigationInset else 0)
        // Keep a window for firmware touchpad routing when there is nothing to display.
        minimumHeight = if (visible) 0 else 1
        for ((slot, view) in wordViews.withIndex()) {
            val index = WORD_ORDER[slot]
            view.visibility = if (showSuggestions && !showPages) VISIBLE else GONE
            view.text = words.getOrElse(index) { "" }
            view.isEnabled = index < words.size
            view.setTextColor(ink)
        }
        val emojiPage = if (showEmoji) emoji?.page().orEmpty() else emptyList()
        val query = if (showEmoji) emoji?.query().orEmpty() else ""
        val searching = query.isNotEmpty()
        val found = emoji?.results() ?: 0
        queryView.visibility = if (searching) VISIBLE else GONE
        queryView.text = SpannableString("$query|").apply {
            setSpan(ForegroundColorSpan(MUTED), query.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        queryView.setTextColor(ink)
        emptyView.visibility = if (searching && found == 0) VISIBLE else GONE
        for ((slot, view) in symbolViews.withIndex()) {
            val value = if (showEmoji) emojiPage.getOrNull(slot) else symbols.getOrNull(page * SYMBOLS_PER_PAGE + slot)
            view.visibility = when {
                !showPages || searching && (found == 0 || slot >= EmojiPages.RESULTS_PER_PAGE) -> GONE
                value == null -> INVISIBLE
                else -> VISIBLE
            }
            view.text = value ?: ""
            view.contentDescription = value?.let { "Insert $it" }
            view.setTextColor(ink)
        }
        contentDescription = when {
            searching -> "Emoji search, $query. ${if (found == 1) "1 emoji" else "$found emoji"}. Push Enter to insert the first."
            showEmoji -> "Emoji, ${emoji?.group().orEmpty()}. Swipe the keyboard sideways for more, or up or down for the next group."
            showSymbols -> "Symbols. Swipe the keyboard sideways for more."
            else -> null
        }
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        render()
    }

    private fun dp(value: Int) = Math.round(value * resources.displayMetrics.density)

    companion object {
        private val WORD_ORDER = listOf(1, 0, 2)
        private const val SYMBOLS_PER_PAGE = EmojiPages.PER_PAGE
        private const val MUTED = 0xFF888888.toInt()
    }
}
