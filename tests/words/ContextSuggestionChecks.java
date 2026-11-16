package com.atlas.engine;

import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class ContextSuggestionChecks {
    public static void main(String[] args) throws Exception {
        TypedPredictions predictions = new TypedPredictions(new StringReader("the\nthanks\nthat\nI\nDon't\nknow\n"),
                word -> switch (word) { case "the" -> 200; case "that" -> 180; case "thanks" -> 100; default -> 120; });

        check(first(predictions.suggest("th", List.of(), 20000, word -> true)).equals("the"),
                "Without context, the most frequent completion must come first");
        Map<String, Double> context = Map.of("thanks", 9d, "that", 5d, "the", 1d);
        check(Arrays.equals(predictions.suggest("th", List.of(), 20000, word -> true, context), new String[] { "thanks", "that", "the" }),
                "Context must decide the order of completions");
        check(first(predictions.suggest("that", List.of(), 20000, word -> true, Map.of("thanks", 9d))).equals("that"),
                "The typed word must stay when nothing completes it");
        TypedPredictions deli = new TypedPredictions(new StringReader("deli\ndelicious\ndelight\nthe\ntheir\n"),
                word -> switch (word) { case "the" -> 220; case "their" -> 180; case "delicious" -> 90; case "delight" -> 100; default -> 80; });
        check(Arrays.equals(deli.suggest("deli", List.of(), 20000, word -> true), new String[] { "delight", "delicious", "deli" }),
                "Longer words must come before the typed word");
        check(Arrays.equals(deli.suggest("the", List.of(), 20000, word -> true), new String[] { "their", "the" }),
                "Longer words must come before the typed word, however common it is");
        check(first(deli.suggest("deli", List.of(), 20000, word -> true, Map.of("delicious", 9d))).equals("delicious"),
                "Context must choose between the typed word and longer words");
        check(first(predictions.suggest("", List.of(), 20000, word -> true, Map.of("know", 9d))).equals("know"),
                "With no letters, context must choose the next word");
        check(!Arrays.asList(predictions.suggest("", List.of(), 20000, word -> !word.equals("know"), Map.of("know", 9d))).contains("know"),
                "Blocked next words must stay hidden");
        check(first(predictions.suggest("th", List.of(), 20000, word -> true, Map.of("missing", 9d))).equals("the"),
                "Words the context model did not rank must fall back to frequency");

        check(predictions.contains("DON’T") && predictions.contains("i") && !predictions.contains("do"),
                "Known words must ignore case and apostrophe style");

        LanguageHabits saved = new LanguageHabits();
        saved.add("thx", 20000);
        check(first(predictions.suggest("th", saved.active(20000), 20000, word -> true, Map.of("the", 9d))).equals("the"),
                "A saved word must not outrank a more frequent word the context prefers");
        check(Arrays.asList(predictions.suggest("th", saved.active(20000), 20000, word -> true, context)).contains("thx"),
                "A saved word must remain available alongside context");
        check(!Arrays.asList(predictions.suggest("", saved.active(20000), 20000, word -> true, Map.of("know", 9d))).contains("thx"),
                "A saved word must not appear in next-word suggestions without any letters");
        check(Arrays.equals(predictions.suggest("t", saved.active(20000), 20000, word -> true), new String[] { "the", "that", "thx", "thanks" }),
                "A saved word must rank by frequency like any other word");
        LanguageHabits used = new LanguageHabits();
        used.add("thx", 20000);
        for (int i = 0; i < 16; i++) used.typed("thx", 20000, false);
        check(first(predictions.suggest("t", used.active(20000), 20000, word -> true, Map.of("thanks", 9d))).equals("thanks"),
                "A much-used saved word must not outrank the word the context prefers");
        TypedPredictions spelling = new TypedPredictions(new StringReader("the\nthis\nthink\nthing\nbecause\nreceive\ntehran\n"),
                word -> switch (word) { case "the" -> 250; case "this" -> 240; case "think" -> 200; case "thing" -> 190;
                    case "because" -> 200; case "receive" -> 150; default -> 60; });
        check(first(spelling.suggest("teh", List.of(), 20000, word -> true)).equals("the"),
                "A common word one typo away must come before rare completions");
        check(Arrays.asList(spelling.suggest("teh", List.of(), 20000, word -> true)).contains("tehran"),
                "Completions must stay available next to spelling fixes");
        check(first(spelling.suggest("becuase", List.of(), 20000, word -> true)).equals("because"),
                "Swapped letters must be offered as a fix");
        check(first(spelling.suggest("hte", List.of(), 20000, word -> true)).equals("the"),
                "Swapped first letters must be offered as a fix");
        check(first(spelling.suggest("recieve", List.of(), 20000, word -> true, Map.of("receive", 1d))).equals("receive"),
                "Fixes must also appear when context ranks the words");
        String[] unfinished = spelling.suggest("thi", List.of(), 20000, word -> true);
        check(unfinished[0].equals("this") && Arrays.asList(unfinished).subList(0, 3).containsAll(List.of("think", "the")),
                "An unfinished word must keep its best completions in view, with one fix beside them");
        check(!Arrays.asList(spelling.suggest("think", List.of(), 20000, word -> true)).contains("thing"),
                "A known word must not get spelling fixes");
        check(!Arrays.asList(spelling.suggest("teh", List.of(), 20000, word -> !word.equals("the"))).contains("the"),
                "Blocked words must not appear as fixes");
        TypedPredictions full = new TypedPredictions(new StringReader("chicken\n"), new StringReader(String.join("\n",
                "dictionary=main:en,locale=en", " word=nuggets,f=72,flags=,originalFreq=72", " word=nugget,f=68,flags=,originalFreq=68",
                " word=nuggety,f=0,flags=,originalFreq=0", " word=nugg,f=40,flags=offensive,originalFreq=40",
                " word=Nugent,f=60,flags=,originalFreq=60", "  shortcut=nuggs,f=whitelist")),
                word -> switch (word) { case "nuggets" -> 72; case "nugget" -> 68; default -> 40; });
        check(Arrays.equals(full.suggest("nugge", List.of(), 20000, word -> true), new String[] { "nuggets", "nugget" }),
                "Words from the full dictionary must complete, without unused, offensive or name entries");
        check(full.contains("chicken") && full.contains("nuggets") && !full.contains("nugent"),
                "Both word lists must count as known words");
        System.out.println("Context suggestion checks passed");
    }

    private static String first(String[] words) { return words.length == 0 ? "" : words[0]; }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
