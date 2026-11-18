package com.atlas.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.ToDoubleFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Dictionary-backed completions and spelling fixes for physical typing; never rewrites text automatically. */
public final class TypedPredictions {
    private static final int SPELLING_SUPPORT = 5;
    private static final int CORRECTION_LENGTH = 3;
    private static final int CORRECTIONS = 3;
    /** A fix goes before the completions only when it is clearly more common than all of them. */
    private static final int CORRECTION_LEAD = 40;
    /** Lowercase words with their accents, sorted by {@link #folded}, so the completions of a prefix sit next to each other. */
    private final String[] keys;
    /** The keys without accents, so "cafe" finds "café". */
    private final String[] folded;
    /** How each word is written, such as "Paris". Most words are written as their key. */
    private final String[] spellings;
    private final float[] frequencies;
    /** The most frequent words, which are all the next word can be when the model has no opinion. */
    private final int[] common;
    private static final int COMMON_WORDS = 64;

    TypedPredictions(Reader source, ToDoubleFunction<String> frequency) throws IOException {
        this(source, null, frequency);
    }

    /** {@code dictionary} is the full word list in combined format; it adds rarer words such as "nuggets" to the context model's list. */
    TypedPredictions(Reader source, Reader dictionary, ToDoubleFunction<String> frequency) throws IOException {
        this(source, dictionary == null ? null : CombinedDictionary.read(dictionary, TypingLanguages.ENGLISH), frequency,
                TypingLanguages.ENGLISH);
    }

    /** {@code source} is the context model's word list, which only English has. */
    TypedPredictions(Reader source, CombinedDictionary dictionary, ToDoubleFunction<String> frequency,
                     TypingLanguages.Language language) throws IOException {
        // In list order, which breaks ties between equally common words: lists put the more common one first.
        Map<String, String> vocabulary = new LinkedHashMap<>();
        if (source != null) {
            BufferedReader reader = new BufferedReader(source);
            String line;
            while ((line = reader.readLine()) != null) {
                String key = key(line, language);
                // The context model's accented words are names and borrowed words, such as "bogotá".
                if (SwipeWords.swipeable(key)) add(vocabulary, key, key);
            }
        }
        if (dictionary != null) for (CombinedDictionary.Entry entry : dictionary.entries)
            add(vocabulary, key(entry.spelling, language), entry.spelling);
        String[] unsorted = vocabulary.keySet().toArray(new String[0]);
        String[] unsortedFolded = new String[unsorted.length];
        for (int i = 0; i < unsorted.length; i++) unsortedFolded[i] = fold(unsorted[i]);
        Map<String, Integer> listed = new HashMap<>();
        for (int i = 0; i < unsorted.length; i++) listed.put(unsorted[i], i);
        Integer[] order = new Integer[unsorted.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.<Integer, String>comparing(i -> unsortedFolded[i]).thenComparing(i -> unsorted[i]));
        keys = new String[order.length];
        folded = new String[order.length];
        spellings = new String[order.length];
        frequencies = new float[order.length];
        for (int i = 0; i < order.length; i++) {
            keys[i] = unsorted[order[i]];
            folded[i] = unsortedFolded[order[i]];
            spellings[i] = vocabulary.get(keys[i]);
            frequencies[i] = (float) frequency.applyAsDouble(keys[i]);
        }
        common = java.util.stream.IntStream.range(0, keys.length).boxed()
                .sorted(Comparator.<Integer>comparingDouble(i -> -frequencies[i]).thenComparing(i -> listed.get(keys[i])))
                .limit(COMMON_WORDS).mapToInt(Integer::intValue).toArray();
    }

    /** The first spelling of a word wins; the dictionary lists the most common one first. */
    private static void add(Map<String, String> vocabulary, String key, String spelling) {
        if (!key.isEmpty() && SuggestionPolicy.allowed(key)) vocabulary.putIfAbsent(key, spelling);
    }

    static String key(String word) { return key(word, TypingLanguages.ENGLISH); }

    private static String key(String word, TypingLanguages.Language language) {
        String lower = lower(word);
        return language.singleLetters.contains(lower) ? lower : SwipeWords.normalise(word);
    }

    private static String lower(String word) {
        return Normalizer.normalize(word, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replace('’', '\'');
    }

    /** The word without accents, letter for letter, so the result has the same length. */
    static String fold(String word) {
        StringBuilder result = null;
        for (int i = 0; i < word.length(); i++) {
            char letter = word.charAt(i), plain = fold(letter);
            if (plain != letter && result == null) result = new StringBuilder(word.substring(0, i));
            if (result != null) result.append(plain);
        }
        return result == null ? word : result.toString();
    }

    private static char fold(char letter) {
        if (letter < 0x80) return letter;
        switch (letter) {
            case 'ł': return 'l';
            case 'ø': return 'o';
            case 'đ': return 'd';
            case 'ħ': return 'h';
            case 'ı': return 'i';
            default: return Normalizer.normalize(String.valueOf(letter), Normalizer.Form.NFD).charAt(0);
        }
    }

    /** Whether the word starts with the typed letters. A letter typed without its accent also matches it with one. */
    private static boolean startsWith(String word, String typed) {
        if (word.length() < typed.length()) return false;
        for (int i = 0; i < typed.length(); i++) {
            char letter = word.charAt(i), key = typed.charAt(i);
            if (letter != key && (fold(key) != key || fold(letter) != key)) return false;
        }
        return true;
    }

    private int indexOf(String key) {
        String plain = fold(key);
        for (int i = lowerBound(plain); i < keys.length && folded[i].equals(plain); i++) if (keys[i].equals(key)) return i;
        return -1;
    }

    boolean contains(String word) {
        return indexOf(lower(word)) >= 0;
    }

    private double frequency(String word) {
        int index = indexOf(word);
        return index < 0 ? 0 : frequencies[index];
    }

    /** How the word is written, such as "Paris" for "paris". */
    private String spelling(String key) {
        int index = indexOf(key);
        return index < 0 ? key : spellings[index];
    }

    public static String prefix(String before) {
        int start = before.length();
        while (start > 0 && (Character.isLetter(before.charAt(start - 1))
                || before.charAt(start - 1) == '\'' || before.charAt(start - 1) == '’')) start--;
        return before.substring(start);
    }

    String[] suggest(String prefix, List<LanguageHabits.Entry> personal, int today, Predicate<String> allowed) {
        return suggest(prefix, personal, today, allowed, Map.of());
    }

    /** {@code context} holds the context model's scores for the words before the cursor. Empty means rank by frequency. */
    String[] suggest(String prefix, List<LanguageHabits.Entry> personal, int today, Predicate<String> allowed, Map<String, Double> context) {
        String lower = lower(prefix);
        Map<String, Double> matches = new LinkedHashMap<>();
        Map<String, Double> similar = new LinkedHashMap<>();
        if (lower.isEmpty()) {
            // Ranking every word takes too long for the next word, and only the model's picks and common words can win.
            for (String word : context.keySet()) {
                int index = indexOf(word);
                if (index >= 0 && allowed.test(word)) matches.put(word, (double) frequencies[index]);
            }
            for (int index : common) if (allowed.test(keys[index])) matches.putIfAbsent(keys[index], (double) frequencies[index]);
        } else {
            String plain = fold(lower);
            for (int i = lowerBound(plain); i < keys.length && folded[i].startsWith(plain); i++)
                if (startsWith(keys[i], lower) && allowed.test(keys[i])) matches.put(keys[i], (double) frequencies[i]);
        }
        // A saved word ranks like any other word, with its own use counted in its frequency.
        for (LanguageHabits.Entry entry : personal) {
            if (!entry.active(today) || !SuggestionPolicy.allowed(entry.word) || !allowed.test(entry.word)) continue;
            double frequency = entry.frequency((float) frequency(entry.word), today);
            // With no letters, only the model's picks and common words take part, so other saved words stay out too.
            if (startsWith(entry.word, lower) && (!lower.isEmpty() || matches.containsKey(entry.word)))
                matches.merge(entry.word, frequency, Math::max);
            else if (entry.support(today) >= SPELLING_SUPPORT && similar(lower, entry.word)) similar.put(entry.word, frequency);
        }
        Comparator<Map.Entry<String, Double>> frequency = Comparator
                .<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey);
        Comparator<Map.Entry<String, Double>> likely = Comparator
                .<Map.Entry<String, Double>>comparingDouble(entry -> context.getOrDefault(entry.getKey(), Double.NEGATIVE_INFINITY))
                .reversed().thenComparing(frequency);
        List<String> result = ranked(matches, likely);
        similar.entrySet().stream().sorted(frequency).findFirst()
                .ifPresent(entry -> result.add(Math.min(2, result.size()), entry.getKey()));
        addCorrections(result, lower, allowed, context, frequency);
        // The typed word is already in the text, so nobody taps it; "deli" leaves the bar to "delicious" and "delight".
        if (result.remove(lower)) result.add(lower);
        return result.stream().limit(8).map(this::spelling).toArray(String[]::new);
    }

    /** Known words one typo away, as "the" is from "teh", for a typed word the dictionary does not know. */
    private void addCorrections(List<String> result, String lower, Predicate<String> allowed, Map<String, Double> context,
                                Comparator<Map.Entry<String, Double>> frequency) {
        if (lower.length() < CORRECTION_LENGTH || contains(lower)) return;
        // A missing accent is no typo, so "cafe" is no distance from "café".
        String plain = fold(lower);
        int limit = plain.length() >= 7 ? 2 : 1;
        Map<String, Integer> distances = new HashMap<>();
        Map<String, Double> found = new LinkedHashMap<>();
        // The words are sorted, so only the runs that start the same way need checking: "h" and the swapped "th" for "hte".
        String swapped = "" + plain.charAt(1) + plain.charAt(0);
        for (String start : plain.charAt(0) == plain.charAt(1) ? List.of(plain.substring(0, 1)) : List.of(plain.substring(0, 1), swapped))
            for (int i = lowerBound(start); i < keys.length && folded[i].startsWith(start); i++) {
                String word = folded[i];
                if (word.length() < 2 || !sameStart(plain, word) || result.contains(keys[i])) continue;
                int distance = distance(plain, word, limit);
                if (distance > limit || !allowed.test(keys[i])) continue;
                distances.put(keys[i], distance);
                found.put(keys[i], (double) frequencies[i]);
            }
        Comparator<Map.Entry<String, Double>> likely = Comparator
                .<Map.Entry<String, Double>>comparingInt(entry -> distances.get(entry.getKey()))
                .thenComparing(Comparator.<Map.Entry<String, Double>>comparingDouble(
                        entry -> context.getOrDefault(entry.getKey(), Double.NEGATIVE_INFINITY)).reversed())
                .thenComparing(frequency);
        List<Map.Entry<String, Double>> fixes = found.entrySet().stream().sorted(likely).limit(CORRECTIONS).toList();
        if (fixes.isEmpty()) return;
        if (result.isEmpty()) {
            fixes.forEach(entry -> result.add(entry.getKey()));
            return;
        }
        // An unfinished word such as "thi" keeps its completions in view, so only one weaker fix shares the visible slots.
        double best = result.stream().mapToDouble(this::frequency).max().orElse(0);
        int front = 0;
        boolean shown = false;
        for (Map.Entry<String, Double> fix : fixes) {
            if (fix.getValue() >= best + CORRECTION_LEAD) result.add(front++, fix.getKey());
            else if (!shown) { result.add(front + 1, fix.getKey()); shown = true; }
            else result.add(fix.getKey());
        }
    }

    private int lowerBound(String prefix) {
        int index = Arrays.binarySearch(folded, prefix);
        if (index < 0) return -index - 1;
        // Several keys can share a folded form, as "cote" and "côté" do, so find the first.
        while (index > 0 && folded[index - 1].equals(prefix)) index--;
        return index;
    }

    /** Typos rarely change the first letter, so a fix keeps it, unless the first two letters swapped as in "hte". */
    private static boolean sameStart(String typed, String word) {
        return typed.charAt(0) == word.charAt(0) || typed.charAt(0) == word.charAt(1) && typed.charAt(1) == word.charAt(0);
    }

    /** Edits between two words, counting a swap of neighbouring letters as one; anything over {@code limit} is limit + 1. */
    static int distance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) return limit + 1;
        int[] twoBack = new int[b.length() + 1], previous = new int[b.length() + 1], next = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            next[0] = i;
            int lowest = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int value = Math.min(Math.min(next[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
                    value = Math.min(value, twoBack[j - 2] + 1);
                next[j] = value;
                lowest = Math.min(lowest, value);
            }
            if (lowest > limit) return limit + 1;
            int[] spare = twoBack; twoBack = previous; previous = next; next = spare;
        }
        return Math.min(previous[b.length()], limit + 1);
    }

    private static List<String> ranked(Map<String, Double> matches, Comparator<Map.Entry<String, Double>> ranking) {
        return matches.entrySet().stream()
                .sorted(ranking)
                .limit(8).map(Map.Entry::getKey).collect(Collectors.toCollection(ArrayList::new));
    }

    private static boolean similar(String typed, String word) {
        if (typed.length() < 2 || typed.length() != word.length() || typed.charAt(0) != word.charAt(0)) return false;
        int differences = 0;
        for (int i = 1; i < typed.length(); i++) {
            if (typed.charAt(i) != word.charAt(i)) {
                if (!Character.isLetter(typed.charAt(i)) || !Character.isLetter(word.charAt(i)) || ++differences > 1) return false;
            }
        }
        return differences == 1;
    }
}
