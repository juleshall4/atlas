package com.atlas.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** What the focused text field allows. */
data class Field(
    /** Swipes and touchpad taps type words. */
    val words: Boolean,
    /** Typed words get suggestions. Nothing is learned unless [learning] is also true. */
    val suggestions: Boolean,
    /** Typing here may teach the keyboard new words. */
    val learning: Boolean,
    /** Two quick spaces after a word become a full stop. */
    val fullStops: Boolean,
    /** A search bar that is empty or fully selected shows starter words. */
    val starters: Boolean,
    /** Sentences may start with a capital letter. */
    val sentenceCapitalisation: Boolean,
    /** The detail the field asks for, such as an email address, which the strip offers from Your details. */
    val detail: Details.Kind? = null,
) {
    companion object {
        val None = Field(words = false, suggestions = false, learning = false, fullStops = false, starters = false,
            sentenceCapitalisation = false)

        fun of(inputType: Int, imeOptions: Int, hint: CharSequence? = null): Field {
            val detail = Details.kind(inputType, hint)
            if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return None.copy(detail = detail)
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val password = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            val url = variation == InputType.TYPE_TEXT_VARIATION_URI
            // A plain text field whose hint asks for an email address gets the same care as an email field.
            val email = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS || detail == Details.Kind.Email
            val address = url || email
            val private = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
            // Browsers often give search bars the URL type.
            val search = url && inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
            return Field(words = !password, suggestions = !password && !email && (!url || search),
                learning = !password && !address && !private,
                fullStops = !password && !address, starters = search,
                sentenceCapitalisation = !password && !address && inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0,
                detail = detail)
        }
    }
}
