package com.atlas.engine;

import java.io.StringReader;
import java.util.Arrays;
import java.util.List;

public final class LanguageChecks {
    public static void main(String[] args) throws Exception {
        TypingLanguages.Language french = TypingLanguages.find("fr");
        CombinedDictionary words = CombinedDictionary.read(new StringReader(String.join("\n",
                "dictionary=main:fr,locale=fr",
                " word=à,f=208,flags=,originalFreq=208",
                " word=café,f=120,flags=,originalFreq=120",
                " word=cafés,f=90,flags=,originalFreq=90",
                " word=côte,f=110,flags=,originalFreq=110",
                " word=cote,f=80,flags=,originalFreq=80",
                " word=Paris,f=152,flags=,originalFreq=152",
                " word=parisien,f=60,flags=,originalFreq=60",
                " word=d'un,f=178,flags=,originalFreq=178",
                " word=été,f=150,flags=,originalFreq=150",
                " word=b,f=40,flags=,originalFreq=40")), french);
        TypedPredictions predictions = new TypedPredictions(null, words, words::frequency, french);

        check(Arrays.asList(predictions.suggest("caf", List.of(), 20000, word -> true)).contains("café"),
                "Typing without accents must find words with them: " + list(predictions.suggest("caf", List.of(), 20000, word -> true)));
        check(first(predictions.suggest("cafe", List.of(), 20000, word -> true)).equals("café"),
                "A word typed without its accent must offer the accented word first");
        check(!Arrays.asList(predictions.suggest("cô", List.of(), 20000, word -> true)).contains("cote"),
                "A typed accent must only match words with that accent");
        check(Arrays.asList(predictions.suggest("co", List.of(), 20000, word -> true)).containsAll(List.of("côte", "cote")),
                "Words that differ only in accents must both complete");
        check(first(predictions.suggest("par", List.of(), 20000, word -> true)).equals("Paris"),
                "A language with capitals in its list must suggest them as written");
        check(Arrays.asList(predictions.suggest("dun", List.of(), 20000, word -> true)).contains("d'un"),
                "A missing apostrophe must be fixed: " + list(predictions.suggest("dun", List.of(), 20000, word -> true)));
        check(predictions.contains("à") && predictions.contains("ÉTÉ") && !predictions.contains("b"),
                "Only the language's one-letter words must count as words");
        check(words.frequency("paris") == 152 && words.frequency("dun") == 178 && words.frequency("nothing") == 0,
                "Frequency must ignore case and apostrophes");

        check(SwipeWords.normalise("Été").equals("été") && SwipeWords.normalise("D’un").equals("d'un"),
                "Words with accents must be valid words");
        check(SwipeWords.normalise("été").equals("été"), "Accents typed as separate marks must join their letters");
        check(SwipeWords.normalise("a1b").isEmpty() && SwipeWords.normalise("x").isEmpty(), "Words must be letters only, two or more");
        check(SwipeWords.swipeable("don't") && !SwipeWords.swipeable("été"), "Only key letters can be swiped");

        LanguageHabits saved = new LanguageHabits();
        saved.add("Hélène", 20000);
        check(Arrays.asList(predictions.suggest("hel", saved.active(20000), 20000, word -> true)).contains("hélène"),
                "A saved word with accents must complete from letters without them");

        CombinedDictionary english = CombinedDictionary.read(new StringReader(String.join("\n",
                "dictionary=main:en,locale=en", " word=Paris,f=152,flags=,originalFreq=152",
                " word=parish,f=90,flags=,originalFreq=90")), TypingLanguages.ENGLISH);
        TypedPredictions englishPredictions = new TypedPredictions(null, english, english::frequency, TypingLanguages.ENGLISH);
        check(Arrays.equals(englishPredictions.suggest("par", List.of(), 20000, word -> true), new String[] { "parish" }),
                "English must leave names to the context model's list");
        check(english.frequency("paris") == 152, "English names must still count as known words");
        System.out.println("Language checks passed");
    }

    private static String first(String[] words) { return words.length == 0 ? "" : words[0]; }

    private static String list(String[] words) { return String.join(", ", words); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
