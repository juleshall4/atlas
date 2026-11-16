package com.atlas.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
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
    /** Sorted, so the completions of a prefix sit next to each other. */
    private final String[] words;
    private final float[] frequencies;
    /** The most frequent words, which are all the next word can be when the model has no opinion. */
    private final int[] common;
    private static final int COMMON_WORDS = 64;

    TypedPredictions(Reader source, ToDoubleFunction<String> frequency) throws IOException {
        this(source, null, frequency);
    }

    /** {@code dictionary} is the full word list in combined format; it adds rarer words such as "nuggets" to the context model's list. */
    TypedPredictions(Reader source, Reader dictionary, ToDoubleFunction<String> frequency) throws IOException {
        Map<String, Double> vocabulary = new HashMap<>();
        BufferedReader reader = new BufferedReader(source);
        String line;
        while ((line = reader.readLine()) != null) add(vocabulary, key(line), frequency);
        if (dictionary != null) {
            reader = new BufferedReader(dictionary);
            while ((line = reader.readLine()) != null) {
                String word = dictionaryWord(line);
                if (word != null) add(vocabulary, key(word), frequency);
            }
        }
        words = vocabulary.keySet().toArray(new String[0]);
        Arrays.sort(words);
        frequencies = new float[words.length];
        for (int i = 0; i < words.length; i++) frequencies[i] = vocabulary.get(words[i]).floatValue();
        common = java.util.stream.IntStream.range(0, words.length).boxed()
                .sorted(Comparator.<Integer>comparingDouble(i -> -frequencies[i]).thenComparing(i -> words[i]))
                .limit(COMMON_WORDS).mapToInt(Integer::intValue).toArray();
    }

    private static void add(Map<String, Double> vocabulary, String word, ToDoubleFunction<String> frequency) {
        if (!word.isEmpty() && SuggestionPolicy.allowed(word) && !vocabulary.containsKey(word))
            vocabulary.put(word, frequency.applyAsDouble(word));
    }

    /** The word on a " word=nuggets,f=72,flags=,…" line, or null for names, unused words, and words marked offensive or not a word. */
    static String dictionaryWord(String line) {
        if (!line.startsWith(" word=")) return null;
        String[] fields = line.trim().split(",");
        if (fields.length < 3 || !fields[1].startsWith("f=") || !fields[2].startsWith("flags=")) return null;
        String word = fields[0].substring(5), flags = fields[2].substring(6);
        if (fields[1].equals("f=0") || flags.contains("offensive") || flags.contains("nonword")) return null;
        return word.equals(word.toLowerCase(Locale.ROOT)) ? word : null;
    }

    static String key(String word) {
        return word.equalsIgnoreCase("a") || word.equalsIgnoreCase("i")
                ? word.toLowerCase(Locale.ROOT) : SwipeWords.normalise(word);
    }

    boolean contains(String word) {
        return Arrays.binarySearch(words, word.toLowerCase(Locale.ROOT).replace('\u2019', '\'')) >= 0;
    }

    private double frequency(String word) {
        int index = Arrays.binarySearch(words, word);
        return index < 0 ? 0 : frequencies[index];
    }

    public static String prefix(String before) {
        int start = before.length();
        while (start > 0 && (Character.isLetter(before.charAt(start - 1))
                || before.charAt(start - 1) == '\'' || before.charAt(start - 1) == '\u2019')) start--;
        return before.substring(start);
    }

    String[] suggest(String prefix, List<LanguageHabits.Entry> personal, int today, Predicate<String> allowed) {
        return suggest(prefix, personal, today, allowed, Map.of());
    }

    /** {@code context} holds the context model's scores for the words before the cursor. Empty means rank by frequency. */
    String[] suggest(String prefix, List<LanguageHabits.Entry> personal, int today, Predicate<String> allowed, Map<String, Double> context) {
        String lower = prefix.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        Map<String, Double> matches = new LinkedHashMap<>();
        Map<String, Double> similar = new LinkedHashMap<>();
        if (lower.isEmpty()) {
            // Ranking every word takes too long for the next word, and only the model's picks and common words can win.
            for (String word : context.keySet()) {
                int index = Arrays.binarySearch(words, word);
                if (index >= 0 && allowed.test(word)) matches.put(word, (double) frequencies[index]);
            }
            for (int index : common) if (allowed.test(words[index])) matches.putIfAbsent(words[index], (double) frequencies[index]);
        } else for (int i = lowerBound(lower); i < words.length && words[i].startsWith(lower); i++)
            if (allowed.test(words[i])) matches.put(words[i], (double) frequencies[i]);
        // A saved word ranks like any other word, with its own use counted in its frequency.
        for (LanguageHabits.Entry entry : personal) {
            if (!entry.active(today) || !SuggestionPolicy.allowed(entry.word) || !allowed.test(entry.word)) continue;
            double frequency = entry.frequency((float) frequency(entry.word), today);
            // With no letters, only the model's picks and common words take part, so other saved words stay out too.
            if (entry.word.startsWith(lower) && (!lower.isEmpty() || matches.containsKey(entry.word)))
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
        return result.stream().limit(8).toArray(String[]::new);
    }

    /** Known words one typo away, as "the" is from "teh", for a typed word the dictionary does not know. */
    private void addCorrections(List<String> result, String lower, Predicate<String> allowed, Map<String, Double> context,
                                Comparator<Map.Entry<String, Double>> frequency) {
        if (lower.length() < CORRECTION_LENGTH || contains(lower)) return;
        int limit = lower.length() >= 7 ? 2 : 1;
        Map<String, Integer> distances = new HashMap<>();
        Map<String, Double> found = new LinkedHashMap<>();
        // The words are sorted, so only the runs that start the same way need checking: "h" and the swapped "th" for "hte".
        String swapped = "" + lower.charAt(1) + lower.charAt(0);
        for (String start : lower.charAt(0) == lower.charAt(1) ? List.of(lower.substring(0, 1)) : List.of(lower.substring(0, 1), swapped))
            for (int i = lowerBound(start); i < words.length && words[i].startsWith(start); i++) {
                String word = words[i];
                if (word.length() < 2 || !sameStart(lower, word) || result.contains(word)) continue;
                int distance = distance(lower, word, limit);
                if (distance > limit || !allowed.test(word)) continue;
                distances.put(word, distance);
                found.put(word, (double) frequencies[i]);
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
        int index = Arrays.binarySearch(words, prefix);
        return index < 0 ? -index - 1 : index;
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
