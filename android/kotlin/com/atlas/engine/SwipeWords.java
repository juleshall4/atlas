package com.atlas.engine;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

final class SwipeWords {
    private static final Pattern WORD = Pattern.compile("\\p{L}+('\\p{L}+)*");

    /** A word in lowercase with straight apostrophes, in any alphabet, or empty when it is not a word of 2 to 24 letters. */
    static String normalise(String word) {
        String value = Normalizer.normalize(word, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        String letters = value.replace("'", "");
        return letters.length() >= 2 && letters.length() <= 24 && WORD.matcher(value).matches() ? value : "";
    }
    /** The swipe engine knows only the letters on the keys. */
    static boolean swipeable(String word) { return word.chars().allMatch(c -> c >= 'a' && c <= 'z' || c == '\''); }
    static String letters(String word) { return normalise(word).replace("'", ""); }
    static boolean valid(float[] x, float[] y, float[] t) {
        if (x.length < 3 || x.length > 4096 || x.length != y.length || x.length != t.length) return false;
        for (int i = 0; i < x.length; i++) {
            if (!Float.isFinite(x[i]) || !Float.isFinite(y[i]) || !Float.isFinite(t[i])
                    || x[i] < 0 || x[i] > 1 || y[i] < 0 || y[i] > 4f / 3 || t[i] < 0
                    || (i > 0 && t[i] < t[i - 1])) return false;
        }
        return t[t.length - 1] - t[0] >= 50 && t[t.length - 1] - t[0] <= 10000;
    }
    private SwipeWords() {}
}
