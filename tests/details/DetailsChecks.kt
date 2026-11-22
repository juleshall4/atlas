package com.atlas.keyboard

import android.text.InputType
import com.atlas.keyboard.Details.Kind

private const val TEXT = InputType.TYPE_CLASS_TEXT

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual != expected) throw AssertionError("$name: got $actual, expected $expected")
}

fun main() {
    check("email type", Details.kind(TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, null), Kind.Email)
    check("web email type", Details.kind(TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, "Username"), Kind.Email)
    check("phone class", Details.kind(InputType.TYPE_CLASS_PHONE, null), Kind.Phone)
    check("person name type", Details.kind(TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME, null), Kind.Name)
    check("person name type, first name hint", Details.kind(TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME, "First name"), Kind.FirstName)
    check("name flags with no class", Details.kind(0x2060, "Name"), Kind.Name)
    check("no input", Details.kind(0, "Name"), null)
    check("email hint", Details.kind(TEXT, "Email address"), Kind.Email)
    check("e-mail hint", Details.kind(TEXT, "E-mail*"), Kind.Email)
    check("mobile hint", Details.kind(TEXT, "Mobile number"), Kind.Phone)
    check("first name hint", Details.kind(TEXT, "First name"), Kind.FirstName)
    check("surname hint", Details.kind(TEXT, "Surname:"), Kind.LastName)
    check("name hint", Details.kind(TEXT, "Name"), Kind.Name)
    check("full name hint", Details.kind(TEXT, "Full name"), Kind.Name)
    // A wrong suggestion is worse than none.
    check("email subject hint", Details.kind(TEXT, "Email subject"), null)
    check("mobile carrier hint", Details.kind(TEXT, "Mobile carrier"), null)
    check("username hint", Details.kind(TEXT, "Username"), null)
    check("name on card hint", Details.kind(TEXT, "Name on card"), null)
    check("company name hint", Details.kind(TEXT, "Company name"), null)
    check("message", Details.kind(TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, "Message"), null)
    check("no hint", Details.kind(TEXT, null), null)
    check("password with email hint", Details.kind(TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "Email password"), null)
    check("number", Details.kind(InputType.TYPE_CLASS_NUMBER, "Phone"), null)

    val emails = listOf("jo@work.com", "jo@home.org", "post@jo.dev")
    check("empty field offers all", Details.matches(emails, ""), emails)
    check("prefix narrows", Details.matches(emails, "jo@h"), listOf("jo@home.org"))
    check("case does not matter", Details.matches(emails, "JO@W"), listOf("jo@work.com"))
    check("finished value is not offered", Details.matches(emails, "post@jo.dev"), emptyList<String>())
    check("other text matches nothing", Details.matches(emails, "x"), emptyList<String>())

    val saved = Details.Saved("Jo", "Bloggs", emails, "")
    check("name offers full and first", saved.values(Kind.Name), listOf("Jo Bloggs", "Jo"))
    check("empty phone offers nothing", saved.values(Kind.Phone), emptyList<String>())
    check("first name only", Details.Saved("Jo", "", emptyList(), "").values(Kind.Name), listOf("Jo"))
}
