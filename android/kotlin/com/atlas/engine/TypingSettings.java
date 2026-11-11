package com.atlas.engine;

import android.content.Context;
import android.content.SharedPreferences;

public final class TypingSettings {
    public static final int MIN_LIFT_OFF_MILLIS = 50, MAX_LIFT_OFF_MILLIS = 200, DEFAULT_LIFT_OFF_MILLIS = 90;
    private static final String DEFAULT_TAP_KEYS = "AI ";

    public static boolean autoCapitalise(Context context) {
        return preferences(context).getBoolean("auto_capitalise", true);
    }

    public static void autoCapitalise(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("auto_capitalise", enabled).apply();
    }

    /** Typed words get a full stop from two spaces, lose the space before punctuation, and gain apostrophes. */
    public static boolean autoPunctuation(Context context) {
        return preferences(context).getBoolean("auto_punctuation", true);
    }

    public static void autoPunctuation(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("auto_punctuation", enabled).apply();
    }

    public static boolean suggestionBar(Context context) {
        return preferences(context).getBoolean("suggestion_bar", true);
    }

    public static void suggestionBar(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("suggestion_bar", enabled).apply();
    }

    /** A swipe across the touchpad types a word. */
    public static boolean swipeTyping(Context context) {
        return preferences(context).getBoolean("swipe_typing", true);
    }

    public static void swipeTyping(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("swipe_typing", enabled).apply();
    }

    /** Resting a finger on Space starts cursor mode: lean towards either end to move the cursor. */
    public static boolean cursorControl(Context context) {
        return preferences(context).getBoolean("cursor_control", true);
    }

    public static void cursorControl(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("cursor_control", enabled).apply();
    }

    /** A slide on the keys outside a text field turns on the keyboard light. */
    public static boolean touchLight(Context context) {
        return preferences(context).getBoolean("touch_light", true);
    }

    public static void touchLight(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("touch_light", enabled).apply();
    }

    /** Holding Alt with a key does an action, such as undo. Off, a held Alt types symbols. */
    public static boolean actions(Context context) {
        return preferences(context).getBoolean("alt_actions", true);
    }

    public static void actions(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("alt_actions", enabled).apply();
    }

    public static boolean liftOffGrace(Context context) { return preferences(context).getBoolean("lift_off_grace", true); }

    public static void liftOffGrace(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("lift_off_grace", enabled).apply();
    }

    public static int liftOffDurationMillis(Context context) {
        return Math.max(MIN_LIFT_OFF_MILLIS, Math.min(MAX_LIFT_OFF_MILLIS,
                preferences(context).getInt("lift_off_duration_ms", DEFAULT_LIFT_OFF_MILLIS)));
    }

    public static void liftOffDurationMillis(Context context, int millis) {
        preferences(context).edit().putInt("lift_off_duration_ms",
                Math.max(MIN_LIFT_OFF_MILLIS, Math.min(MAX_LIFT_OFF_MILLIS, millis))).apply();
    }

    public static int liftOffGraceMillis(Context context) {
        return liftOffGrace(context) ? liftOffDurationMillis(context) : 0;
    }

    /** The letters and space a light touch types, in the order they were added. */
    public static String tapKeys(Context context) {
        return preferences(context).getString("tap_keys", DEFAULT_TAP_KEYS);
    }

    public static void tapKeys(Context context, String keys) {
        StringBuilder unique = new StringBuilder();
        for (char key : keys.toCharArray())
            if ((key == ' ' || key >= 'A' && key <= 'Z') && unique.indexOf(String.valueOf(key)) < 0) unique.append(key);
        preferences(context).edit().putString("tap_keys", unique.toString()).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("typing_settings", Context.MODE_PRIVATE);
    }

    public static EnglishSpelling.Variant spelling(Context context) {
        return "american".equals(preferences(context).getString("spelling", "british"))
                ? EnglishSpelling.Variant.AMERICAN : EnglishSpelling.Variant.BRITISH;
    }

    public static void spelling(Context context, EnglishSpelling.Variant variant) {
        preferences(context).edit().putString("spelling",
                variant == EnglishSpelling.Variant.AMERICAN ? "american" : "british").apply();
    }

    /** The skin tone of emoji that take one. */
    public static EmojiPages.Tone skinTone(Context context) {
        return EmojiPages.Tone.of(preferences(context).getString("skin_tone", EmojiPages.Tone.DEFAULT.id));
    }

    public static void skinTone(Context context, EmojiPages.Tone tone) {
        preferences(context).edit().putString("skin_tone", tone.id).apply();
    }

    private TypingSettings() {}
}
