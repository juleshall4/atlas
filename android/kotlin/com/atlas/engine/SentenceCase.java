package com.atlas.engine;

import java.util.Locale;
import java.util.Set;

/** Capitalises the first letter of each sentence. */
public final class SentenceCase {
    // A full stop after these does not end the sentence.
    private static final Set<String> ABBREVIATIONS = Set.of("e.g.", "i.e.", "eg.", "ie.", "vs.", "cf.", "viz.");

    public static String capitalise(String text, String before) {
        StringBuilder result = new StringBuilder(before);
        for (int index = 0; index < text.length();) {
            int character = text.codePointAt(index);
            result.appendCodePoint(startsSentence(result) ? Character.toTitleCase(character) : character);
            index += Character.charCount(character);
        }
        return result.substring(before.length());
    }

    /** Shift at the start of a sentence gives lowercase, so "iPhone" or "eBay" can start one. */
    public static String uncapitalise(String text, String before) {
        if (text.isEmpty() || !startsSentence(before)) return text;
        int first = text.codePointAt(0);
        return new StringBuilder().appendCodePoint(Character.toLowerCase(first))
                .append(text, Character.charCount(first), text.length()).toString();
    }

    /** A full stop must have a space after it, so "e.g" and "atlas.com" stay in lowercase. */
    static boolean startsSentence(CharSequence before) {
        boolean spaced = false;
        for (int end = before.length(); end > 0;) {
            int previous = Character.codePointBefore(before, end);
            if (lineBreak(previous)) return true;
            // Some fields, like WhatsApp search, start with an invisible character such as a zero-width space.
            if (Character.getType(previous) == Character.FORMAT) {
                end -= Character.charCount(previous);
            } else if (Character.isWhitespace(previous)) {
                spaced = true;
                end -= Character.charCount(previous);
            } else {
                // Full-width marks do not take a space.
                if ("\u3002\uff01\uff1f".indexOf(previous) >= 0) return true;
                return ".!?".indexOf(previous) >= 0 && spaced && !abbreviation(before, end);
            }
        }
        return true;
    }

    private static boolean abbreviation(CharSequence before, int end) {
        int start = end;
        while (start > 0 && !Character.isWhitespace(before.charAt(start - 1))) start--;
        return ABBREVIATIONS.contains(before.subSequence(start, end).toString().toLowerCase(Locale.ROOT));
    }

    private static boolean lineBreak(int character) {
        return "\n\r  ".indexOf(character) >= 0;
    }

    private SentenceCase() {}
}
