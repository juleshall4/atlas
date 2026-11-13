package com.atlas.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The emoji the strip offers while Sym is on, in groups of pages. The recent emoji come first, at the start of the first group.
 * Sideways swipes turn pages through the whole list; up and down swipes jump between groups.
 * A search shows only the emoji whose name or words match, best first.
 * Pages show each emoji in the chosen skin tone, and the recent emoji keep the emoji without a tone.
 */
public final class EmojiPages {
    public static final int PER_PAGE = 8;
    /** A search shares the strip with its query. */
    public static final int RESULTS_PER_PAGE = 6;
    public static final int RECENT_LIMIT = 8;

    public record Group(String name, List<String> emoji) {}

    /** The words that find an emoji: its name and the CLDR keywords. */
    public record Words(String name, List<String> keywords) {}

    public enum Tone {
        DEFAULT("default"), LIGHT("light"), MEDIUM_LIGHT("medium-light"), MEDIUM("medium"), MEDIUM_DARK("medium-dark"), DARK("dark");

        public final String id;

        Tone(String id) {
            this.id = id;
        }

        public static Tone of(String id) {
            for (Tone tone : values()) if (tone.id.equals(id)) return tone;
            return DEFAULT;
        }
    }

    private record Page(int group, int start) {}

    private final List<Group> groups;
    private final Set<String> known = new HashSet<>();
    // Search runs on every key press, so each emoji's words sit in flat arrays, in list order.
    private final String[] all;
    private final String[] names;
    private final String[][] nameWords;
    private final String[][] allWords;
    /** The matches of each query since the emoji opened, so Backspace needs no new search. */
    private final Map<String, int[]> matches = new HashMap<>();
    private final Map<String, List<String>> tones;
    private final Map<String, String> bases = new HashMap<>();
    private final List<String> recents = new ArrayList<>();
    private final List<Group> shown = new ArrayList<>();
    private final List<Page> pages = new ArrayList<>();
    private Tone tone = Tone.DEFAULT;
    private String query = "";
    private int results;
    private int perPage = PER_PAGE;
    private int page;

    public EmojiPages(List<Group> groups, List<String> recents, Map<String, Words> words, Map<String, List<String>> tones) {
        this.groups = List.copyOf(groups);
        for (Group group : groups) known.addAll(group.emoji());
        all = groups.stream().flatMap(group -> group.emoji().stream()).distinct().toArray(String[]::new);
        names = new String[all.length];
        nameWords = new String[all.length][];
        allWords = new String[all.length][];
        for (int index = 0; index < all.length; index++) {
            Words found = words.get(all[index]);
            if (found == null) {
                names[index] = "";
                nameWords[index] = allWords[index] = new String[0];
                continue;
            }
            nameWords[index] = words(found.name());
            names[index] = String.join(" ", nameWords[index]);
            List<String> every = new ArrayList<>(List.of(nameWords[index]));
            for (String keyword : found.keywords()) every.addAll(List.of(words(keyword)));
            allWords[index] = every.stream().distinct().toArray(String[]::new);
        }
        this.tones = Map.copyOf(tones);
        for (Map.Entry<String, List<String>> entry : tones.entrySet()) {
            for (String form : entry.getValue()) bases.put(form, entry.getKey());
        }
        for (String emoji : new LinkedHashSet<>(recents)) {
            String base = bases.getOrDefault(emoji, emoji);
            if (known.contains(base) && !this.recents.contains(base) && this.recents.size() < RECENT_LIMIT) this.recents.add(base);
        }
        reset();
    }

    /** Reads one group per line, a name and its emoji split by spaces, keeping only emoji the phone can show. */
    public static List<Group> parse(Reader source, Predicate<String> shown) throws IOException {
        List<Group> groups = new ArrayList<>();
        BufferedReader lines = new BufferedReader(source);
        for (String line = lines.readLine(); line != null; line = lines.readLine()) {
            int tab = line.indexOf('\t');
            if (line.startsWith("# ") || tab < 0) continue;
            List<String> emoji = new ArrayList<>();
            for (String item : line.substring(tab + 1).split(" ")) if (!item.isEmpty() && shown.test(item)) emoji.add(item);
            if (!emoji.isEmpty()) groups.add(new Group(line.substring(0, tab), List.copyOf(emoji)));
        }
        return groups;
    }

    /** Reads one emoji per line: the emoji, its name, and its keywords split by "|". */
    public static Map<String, Words> parseWords(Reader source) throws IOException {
        Map<String, Words> words = new HashMap<>();
        BufferedReader lines = new BufferedReader(source);
        for (String line = lines.readLine(); line != null; line = lines.readLine()) {
            String[] fields = line.split("\t", -1);
            if (line.startsWith("# ") || fields.length < 3) continue;
            List<String> keywords = fields[2].isEmpty() ? List.of() : List.of(fields[2].split("\\|"));
            words.put(fields[0], new Words(fields[1], keywords));
        }
        return words;
    }

    /** Reads one emoji per line with its five toned forms, light to dark. An emoji the phone cannot show in every tone keeps no tones. */
    public static Map<String, List<String>> parseTones(Reader source, Predicate<String> shown) throws IOException {
        Map<String, List<String>> tones = new HashMap<>();
        BufferedReader lines = new BufferedReader(source);
        for (String line = lines.readLine(); line != null; line = lines.readLine()) {
            int tab = line.indexOf('\t');
            if (line.startsWith("# ") || tab < 0) continue;
            List<String> forms = List.of(line.substring(tab + 1).split(" "));
            if (forms.size() == Tone.values().length - 1 && forms.stream().allMatch(shown)) tones.put(line.substring(0, tab), forms);
        }
        return tones;
    }

    public void tone(Tone tone) {
        this.tone = tone;
    }

    /** The emoji as the pages show it, in the chosen skin tone if it takes one. */
    public String shown(String emoji) {
        List<String> forms = tone == Tone.DEFAULT ? null : tones.get(emoji);
        return forms == null ? emoji : forms.get(tone.ordinal() - 1);
    }

    /** Back to the first page of the groups, with the recent emoji as they are now. */
    public void reset() {
        query = "";
        matches.clear();
        shown.clear();
        shown.addAll(groups);
        if (!shown.isEmpty() && !recents.isEmpty()) {
            List<String> first = new ArrayList<>(recents);
            first.addAll(shown.get(0).emoji());
            shown.set(0, new Group(shown.get(0).name(), List.copyOf(first)));
        }
        paginate(PER_PAGE);
    }

    /** Shows the emoji that match [query], best first, from the first page. An empty query shows the groups again. */
    public void search(String query) {
        if (query.isEmpty()) {
            reset();
            return;
        }
        this.query = query;
        String[] words = words(query);
        String phrase = String.join(" ", words);
        int[] found = match(words, phrase);
        // Best rank first. Ties keep list order, which the index in the low bits gives.
        long[] ranked = new long[found.length];
        for (int i = 0; i < found.length; i++) ranked[i] = (long) rank(found[i], words, phrase) << 32 | found[i];
        Arrays.sort(ranked);
        List<String> ordered = new ArrayList<>(found.length);
        for (long entry : ranked) ordered.add(all[(int) entry]);
        shown.clear();
        shown.add(new Group("", ordered));
        paginate(RESULTS_PER_PAGE);
    }

    /** The emoji, by index in list order, whose words start with each query word. */
    private int[] match(String[] words, String phrase) {
        int[] found = matches.get(phrase);
        if (found != null) return found;
        // A longer query only narrows the matches, so a typed letter checks only the last query's matches.
        int[] from = null;
        for (int end = phrase.length() - 1; end > 0 && from == null; end--) from = matches.get(phrase.substring(0, end));
        int size = from == null ? all.length : from.length;
        int[] kept = new int[size];
        int count = 0;
        for (int i = 0; i < size; i++) {
            int index = from == null ? i : from[i];
            if (words.length > 0 && startsEach(allWords[index], words)) kept[count++] = index;
        }
        found = Arrays.copyOf(kept, count);
        matches.put(phrase, found);
        return found;
    }

    public String query() {
        return query;
    }

    public boolean searching() {
        return !query.isEmpty();
    }

    /** How many emoji the search found. */
    public int results() {
        return results;
    }

    /** The best match of the search, as shown, or null when nothing matches. */
    public String top() {
        return searching() && results > 0 ? shown(shown.get(0).emoji().get(0)) : null;
    }

    /** 0 for a name that is the query, 1 for a name that starts with it, 2 when each query word starts a word of the name, else 3. */
    private int rank(int index, String[] words, String phrase) {
        if (names[index].equals(phrase)) return 0;
        if (names[index].startsWith(phrase)) return 1;
        return startsEach(nameWords[index], words) ? 2 : 3;
    }

    /** Each query word starts one of [words]. */
    private static boolean startsEach(String[] words, String[] starts) {
        for (String start : starts) {
            boolean found = false;
            for (String word : words) {
                if (word.startsWith(start)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /** Lowercase words with no accents, so "pinata" finds the piñata. */
    private static String[] words(String text) {
        String plain = text;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                plain = Normalizer.normalize(text, Normalizer.Form.NFD);
                break;
            }
        }
        List<String> words = new ArrayList<>(4);
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < plain.length(); ) {
            int character = plain.codePointAt(i);
            i += Character.charCount(character);
            int type = Character.getType(character);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK) continue;
            if (Character.isLetterOrDigit(character)) {
                word.appendCodePoint(Character.toLowerCase(character));
            } else if (word.length() > 0) {
                words.add(word.toString());
                word.setLength(0);
            }
        }
        if (word.length() > 0) words.add(word.toString());
        return words.toArray(new String[0]);
    }

    private void paginate(int size) {
        perPage = size;
        pages.clear();
        results = 0;
        for (int group = 0; group < shown.size(); group++) {
            results += shown.get(group).emoji().size();
            for (int start = 0; start < shown.get(group).emoji().size(); start += perPage) pages.add(new Page(group, start));
        }
        page = 0;
    }

    /** The emoji on the open page, as shown. */
    public List<String> page() {
        if (pages.isEmpty()) return List.of();
        Page current = pages.get(page);
        List<String> emoji = shown.get(current.group()).emoji();
        return emoji.subList(current.start(), Math.min(emoji.size(), current.start() + perPage)).stream().map(this::shown).toList();
    }

    public String group() {
        return pages.isEmpty() || searching() ? "" : shown.get(pages.get(page).group()).name();
    }

    /** The next or previous page, wrapping at either end. */
    public void turn(int direction) {
        if (!pages.isEmpty()) page = Math.floorMod(page + direction, pages.size());
    }

    /** The first page of the next or previous group, wrapping at either end. A search has no groups. */
    public void jump(int direction) {
        if (pages.isEmpty() || searching()) return;
        int group = Math.floorMod(pages.get(page).group() + direction, shown.size());
        for (int index = 0; index < pages.size(); index++) {
            if (pages.get(index).group() == group) {
                page = index;
                return;
            }
        }
    }

    /** Records a picked emoji, in any tone, and returns the recent emoji to save. The open page stays the same until {@link #reset}. */
    public List<String> use(String emoji) {
        String base = bases.getOrDefault(emoji, emoji);
        if (!known.contains(base)) return List.copyOf(recents);
        recents.remove(base);
        recents.add(0, base);
        if (recents.size() > RECENT_LIMIT) recents.remove(RECENT_LIMIT);
        return List.copyOf(recents);
    }
}
