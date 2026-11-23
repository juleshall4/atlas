package com.atlas.keyboard

import android.content.Context
import android.text.InputType

/** Your details: email addresses and a phone number that the strip offers in form fields that ask for them. */
object Details {
    enum class Kind { Email, Phone }

    class Saved(val emails: List<String>, val phone: String) {
        fun values(kind: Kind) = when (kind) {
            Kind.Email -> emails
            Kind.Phone -> listOf(phone)
        }.filter { it.isNotEmpty() }
    }

    const val MAX_EMAILS = 3
    /** The longest an email address can be. */
    const val MAX_LENGTH = 254

    /**
     * What a field asks for, from its input type, or else from its hint, such as "Email address". Null when it is not
     * clear, because a wrong suggestion is worse than none.
     */
    fun kind(inputType: Int, hint: CharSequence?): Kind? {
        val type = inputType and InputType.TYPE_MASK_CLASS
        if (type == InputType.TYPE_CLASS_PHONE) return Kind.Phone
        if (type != InputType.TYPE_CLASS_TEXT) return null
        val words = hint?.toString()?.lowercase()?.replace(Regex("[^a-z]+"), " ")?.trim().orEmpty()
        when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> return null
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> return Kind.Email
        }
        return when {
            words.isEmpty() -> null
            // The whole hint must ask for the detail: "Email subject" is not an email address.
            Regex("^(your )?(email|e mail)( address)?$").matches(words) -> Kind.Email
            Regex("^(your )?(mobile|phone|telephone|mobile phone)( number| no)?$").matches(words) -> Kind.Phone
            else -> null
        }
    }

    /** The values that finish what is already in the field. An empty field gets them all. */
    fun matches(values: List<String>, before: String) =
        values.filter { it.length > before.length && it.startsWith(before, ignoreCase = true) }

    fun saved(context: Context): Saved {
        val preferences = preferences(context)
        return Saved(preferences.getString(EMAILS, "").orEmpty().split("\n").filter { it.isNotEmpty() },
            preferences.getString(PHONE, "").orEmpty())
    }

    fun save(context: Context, details: Saved) {
        val emails = details.emails.map(::clean).filter { it.isNotEmpty() }.distinct()
        require(emails.size <= MAX_EMAILS) { "You can add up to $MAX_EMAILS email addresses" }
        preferences(context).edit()
            .putString(EMAILS, emails.joinToString("\n"))
            .putString(PHONE, clean(details.phone))
            .apply()
    }

    private fun clean(value: String) = value.replace(Regex("\\s+"), " ").trim().take(MAX_LENGTH)

    // Kept apart from the typing settings and the learned words, so a words export never holds them.
    private fun preferences(context: Context) = context.getSharedPreferences("details", Context.MODE_PRIVATE)

    private const val EMAILS = "emails"
    private const val PHONE = "phone"
}
