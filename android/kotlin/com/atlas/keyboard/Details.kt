package com.atlas.keyboard

import android.content.Context
import android.text.InputType

/** Your details: names, email addresses and a phone number that the strip offers in form fields that ask for them. */
object Details {
    enum class Kind { Email, Name, FirstName, LastName, Phone }

    class Saved(val firstName: String, val lastName: String, val emails: List<String>, val phone: String) {
        val fullName get() = listOf(firstName, lastName).filter { it.isNotEmpty() }.joinToString(" ")

        fun values(kind: Kind) = when (kind) {
            Kind.Email -> emails
            Kind.Name -> listOf(fullName, firstName)
            Kind.FirstName -> listOf(firstName)
            Kind.LastName -> listOf(lastName)
            Kind.Phone -> listOf(phone)
        }.filter { it.isNotEmpty() }.distinct()
    }

    const val MAX_EMAILS = 3
    /** The longest an email address can be. */
    const val MAX_LENGTH = 254

    /**
     * What a field asks for, from its input type, or else from its hint, such as "First name". Null when it is not
     * clear, because a wrong suggestion is worse than none.
     */
    fun kind(inputType: Int, hint: CharSequence?): Kind? {
        val type = inputType and InputType.TYPE_MASK_CLASS
        if (type == InputType.TYPE_CLASS_PHONE) return Kind.Phone
        // Some apps, such as Google Contacts, give a name field text flags but no class.
        if (type != InputType.TYPE_CLASS_TEXT && (type != 0 || inputType == 0)) return null
        val words = hint?.toString()?.lowercase()?.replace(Regex("[^a-z]+"), " ")?.trim().orEmpty()
        when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> return null
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> return Kind.Email
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> return nameKind(words) ?: Kind.Name
        }
        return when {
            words.isEmpty() -> null
            // The whole hint must ask for the detail: "Email subject" is not an email address.
            Regex("^(your )?(email|e mail)( address)?$").matches(words) -> Kind.Email
            Regex("^(your )?(mobile|phone|telephone|mobile phone)( number| no)?$").matches(words) -> Kind.Phone
            else -> nameKind(words)
        }
    }

    private fun nameKind(words: String) = when {
        Regex("^(your )?(first name|given name|forename)$").matches(words) -> Kind.FirstName
        Regex("^(your )?(last name|surname|family name)$").matches(words) -> Kind.LastName
        Regex("^(your )?(full )?name$").matches(words) -> Kind.Name
        else -> null
    }

    /** The values that finish what is already in the field. An empty field gets them all. */
    fun matches(values: List<String>, before: String) =
        values.filter { it.length > before.length && it.startsWith(before, ignoreCase = true) }

    fun saved(context: Context): Saved {
        val preferences = preferences(context)
        return Saved(preferences.getString(FIRST_NAME, "").orEmpty(), preferences.getString(LAST_NAME, "").orEmpty(),
            preferences.getString(EMAILS, "").orEmpty().split("\n").filter { it.isNotEmpty() },
            preferences.getString(PHONE, "").orEmpty())
    }

    fun save(context: Context, details: Saved) {
        val emails = details.emails.map(::clean).filter { it.isNotEmpty() }.distinct()
        require(emails.size <= MAX_EMAILS) { "You can add up to $MAX_EMAILS email addresses" }
        preferences(context).edit()
            .putString(FIRST_NAME, clean(details.firstName))
            .putString(LAST_NAME, clean(details.lastName))
            .putString(EMAILS, emails.joinToString("\n"))
            .putString(PHONE, clean(details.phone))
            .apply()
    }

    private fun clean(value: String) = value.replace(Regex("\\s+"), " ").trim().take(MAX_LENGTH)

    // Kept apart from the typing settings and the learned words, so a words export never holds them.
    private fun preferences(context: Context) = context.getSharedPreferences("details", Context.MODE_PRIVATE)

    private const val FIRST_NAME = "first_name"
    private const val LAST_NAME = "last_name"
    private const val EMAILS = "emails"
    private const val PHONE = "phone"
}
