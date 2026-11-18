package com.atlas.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A word list in the AOSP combined format, with lines such as " word=nuggets,f=72,flags=,originalFreq=72".
 * It gives the words to suggest, and how common any listed word is.
 */
final class CombinedDictionary {
    /** A word to suggest, in its dictionary spelling, such as "Paris" or "d'un". */
    static final class Entry {
        final String spelling;
        final float frequency;
        Entry(String spelling, float frequency) { this.spelling = spelling; this.frequency = frequency; }
    }

    final List<Entry> entries;
    /** Every listed word as lowercase letters only, sorted, with the highest frequency of its spellings. */
    private final String[] letters;
    private final byte[] frequencies;

    private CombinedDictionary(List<Entry> entries, String[] letters, byte[] frequencies) {
        this.entries = entries; this.letters = letters; this.frequencies = frequencies;
    }

    static CombinedDictionary read(Reader source, TypingLanguages.Language language) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Map<String, Integer> highest = new HashMap<>();
        BufferedReader reader = new BufferedReader(source);
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith(" word=")) continue;
            String[] fields = line.trim().split(",");
            if (fields.length < 2 || !fields[1].startsWith("f=")) continue;
            int frequency;
            try { frequency = Math.max(0, Math.min(255, Integer.parseInt(fields[1].substring(2)))); }
            catch (NumberFormatException error) { continue; }
            String word = Normalizer.normalize(fields[0].substring(5), Normalizer.Form.NFC);
            String key = letters(word);
            if (!key.isEmpty()) highest.merge(key, frequency, Math::max);
            String flags = fields.length > 2 && fields[2].startsWith("flags=") ? fields[2].substring(6) : "";
            if (frequency == 0 || flags.contains("offensive") || flags.contains("nonword")) continue;
            // English names come from the context model's list instead, which knows which are only names.
            if (!language.capitals && !word.equals(word.toLowerCase(Locale.ROOT))) continue;
            // The English list's accented words are mostly names and borrowed words, such as "bogotá".
            if (language.english && !SwipeWords.swipeable(word)) continue;
            entries.add(new Entry(word, frequency));
        }
        String[] sorted = highest.keySet().toArray(new String[0]);
        Arrays.sort(sorted);
        byte[] frequencies = new byte[sorted.length];
        for (int i = 0; i < sorted.length; i++) frequencies[i] = (byte) (int) highest.get(sorted[i]);
        return new CombinedDictionary(entries, sorted, frequencies);
    }

    /** How common the word is in any spelling, ignoring case and apostrophes, or 0 when the list does not have it. */
    float frequency(String word) {
        int index = Arrays.binarySearch(letters, letters(word));
        return index < 0 ? 0 : frequencies[index] & 0xff;
    }

    private static String letters(String word) {
        StringBuilder result = new StringBuilder(word.length());
        String lower = word.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) if (Character.isLetter(lower.charAt(i))) result.append(lower.charAt(i));
        return result.toString();
    }
}
