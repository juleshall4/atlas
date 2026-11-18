package com.atlas.engine;

public final class SentenceCaseChecks {
    public static void main(String[] args) {
        check("hello", "", "Hello");
        check("hello", "Hi. ", "Hello");
        check("hello", "Hi!\n", "Hello");
        check("hello", "Hi?  ", "Hello");
        check("hello", "Hi, ", "hello");
        check("g", "for example e.", "g");
        check("g.", "for example e.", "g.");
        check("this", "for example e.g. ", "this");
        check("that", "I.e. ", "that");
        check("com", "atlas.", "com");
        check("hello", "Hi.", "hello");
        check("hello", "Hi。", "Hello");
        check("hi. you", "", "Hi. You");
        check("e.g. you", "", "E.g. you");
        check("hello", "​", "Hello");
        uncapitalised("Iphone", "", "iphone");
        uncapitalised("Ebay", "Hi. ", "ebay");
        uncapitalised("H", "Hi ", "H");
        uncapitalised("G", "e.", "G");
        System.out.println("Sentence case checks passed");
    }

    private static void uncapitalised(String text, String before, String expected) {
        String actual = SentenceCase.uncapitalise(text, before);
        if (!actual.equals(expected)) {
            throw new AssertionError("Shift: \"" + before + "\" + \"" + text + "\" gave \"" + actual + "\", expected \"" + expected + "\"");
        }
    }

    private static void check(String text, String before, String expected) {
        String actual = SentenceCase.capitalise(text, before);
        if (!actual.equals(expected)) {
            throw new AssertionError("\"" + before + "\" + \"" + text + "\" gave \"" + actual + "\", expected \"" + expected + "\"");
        }
    }
}
