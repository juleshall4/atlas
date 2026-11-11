package com.atlas.engine;

import java.io.FileReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class EmojiPagesChecks {
    public static void main(String[] args) throws Exception {
        String source = "# comment\nFaces\t😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃\nFood\t🍏 🍎 🫜\nFlags\t🇬🇧 🏴‍☠️\n";
        List<EmojiPages.Group> groups = EmojiPages.parse(new StringReader(source), emoji -> !emoji.equals("🫜"));
        check(groups.size() == 3 && groups.get(0).name().equals("Faces"), "Each line must be one group, in order");
        check(groups.get(1).emoji().equals(List.of("🍏", "🍎")), "Emoji the phone cannot show must be left out");
        check(groups.get(2).emoji().equals(List.of("🇬🇧", "🏴‍☠️")), "Multi-part emoji must stay whole");

        EmojiPages pages = new EmojiPages(groups, List.of(), Map.of(), Map.of());
        check(pages.page().equals(List.of("😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂")), "The first page must hold 8 emoji");
        check(pages.group().equals("Faces"), "With no recent emoji the list must start at the first group");
        pages.turn(1);
        check(pages.page().equals(List.of("🙂", "🙃")), "Turning must show the rest of the group");
        pages.turn(1);
        check(pages.group().equals("Food"), "Turning past a group's last page must go on to the next group");
        pages.turn(-1);
        pages.turn(-1);
        pages.turn(-1);
        check(pages.group().equals("Flags"), "Turning back from the first page must wrap to the last");

        pages.jump(1);
        check(pages.group().equals("Faces") && pages.page().get(0).equals("😀"), "Jumping past the last group must wrap");
        pages.turn(1);
        pages.jump(1);
        check(pages.group().equals("Food"), "Jumping must go to the next group's first page");
        pages.jump(-1);
        check(pages.group().equals("Faces") && pages.page().get(0).equals("😀"), "Jumping back must open the group's first page");

        List<String> saved = new ArrayList<>();
        for (int i = 0; i < 10; i++) saved = pages.use(groups.get(0).emoji().get(i));
        saved = pages.use("😀");
        check(saved.size() == EmojiPages.RECENT_LIMIT && saved.get(0).equals("😀") && saved.indexOf("😀") == saved.lastIndexOf("😀"),
                "Recent emoji must hold the last 8 distinct emoji, newest first");
        check(pages.group().equals("Faces"), "Picking an emoji must not move the open page");
        pages.reset();
        check(pages.group().equals("Faces") && pages.page().equals(saved), "Reopening must start at the recent emoji");
        pages.jump(-1);
        check(pages.group().equals("Flags"), "The recent emoji must not add a group to jump through");

        EmojiPages restored = new EmojiPages(groups, List.of("🍎", "🫜", "", "🍎"), Map.of(), Map.of());
        check(restored.page().equals(List.of("🍎", "😀", "😃", "😄", "😁", "😆", "😅", "🤣")),
                "A few recent emoji must share the first page with the first group");
        restored.turn(1);
        check(restored.page().equals(List.of("😂", "🙂", "🙃")), "The first group must carry on after the recent emoji");

        search();
        tones();

        if (args.length > 0) {
            try (FileReader asset = new FileReader(args[0], StandardCharsets.UTF_8)) {
                List<EmojiPages.Group> all = EmojiPages.parse(asset, emoji -> true);
                int count = all.stream().mapToInt(group -> group.emoji().size()).sum();
                check(all.size() == 9 && count > 1500, "The emoji asset must hold every group: " + all.size() + ", " + count);
                check(all.stream().flatMap(group -> group.emoji().stream()).noneMatch(emoji -> emoji.contains("🏻")),
                        "The emoji asset must leave out skin tone variants");
                try (FileReader wordAsset = new FileReader(args[1], StandardCharsets.UTF_8);
                     FileReader toneAsset = new FileReader(args[2], StandardCharsets.UTF_8)) {
                    Map<String, EmojiPages.Words> words = EmojiPages.parseWords(wordAsset);
                    check(all.stream().flatMap(group -> group.emoji().stream()).allMatch(words::containsKey),
                            "The word asset must name every emoji");
                    Map<String, List<String>> tones = EmojiPages.parseTones(toneAsset, emoji -> true);
                    check(tones.get("👍").equals(List.of("👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿")) && tones.containsKey("✌️") && tones.size() > 300,
                            "The tone asset must give each tone of the hands and people");
                    EmojiPages real = new EmojiPages(all, List.of(), words, tones);
                    real.search("thumbs up");
                    check("👍".equals(real.top()), "Searching \"thumbs up\" must find 👍 first: " + real.top());
                    real.search("lol");
                    check(real.results() > 0 && real.page().contains("😂"), "Searching a keyword such as \"lol\" must find 😂");
                    real.search("pizza");
                    check("🍕".equals(real.top()), "Searching \"pizza\" must find 🍕 first: " + real.top());
                }
            }
        }
        System.out.println("Emoji page checks passed");
    }

    private static void search() {
        List<EmojiPages.Group> groups = List.of(
                new EmojiPages.Group("Faces", List.of("😀", "😂", "🙂")),
                new EmojiPages.Group("Hands", List.of("👍", "👎", "👋")),
                new EmojiPages.Group("Food", List.of("🥧", "🍕", "🪅", "🥟", "🍍", "🐖", "🐷", "🥨", "🫛")));
        Map<String, EmojiPages.Words> words = Map.ofEntries(
                Map.entry("😀", new EmojiPages.Words("grinning face", List.of("face", "grin", "happy"))),
                Map.entry("😂", new EmojiPages.Words("face with tears of joy", List.of("laugh", "lol", "happy"))),
                Map.entry("🙂", new EmojiPages.Words("slightly smiling face", List.of("face", "smile"))),
                Map.entry("👍", new EmojiPages.Words("thumbs up", List.of("+1", "good", "hand", "up"))),
                Map.entry("👎", new EmojiPages.Words("thumbs down", List.of("-1", "bad", "hand", "down"))),
                Map.entry("👋", new EmojiPages.Words("waving hand", List.of("hello", "hand", "wave"))),
                Map.entry("🥧", new EmojiPages.Words("pie", List.of("pastry"))),
                Map.entry("🍕", new EmojiPages.Words("pizza", List.of("cheese", "slice"))),
                Map.entry("🪅", new EmojiPages.Words("piñata", List.of("party"))),
                Map.entry("🥟", new EmojiPages.Words("dumpling", List.of("pierogi"))),
                Map.entry("🍍", new EmojiPages.Words("pineapple", List.of("fruit"))),
                Map.entry("🐖", new EmojiPages.Words("pig", List.of("sow"))),
                Map.entry("🐷", new EmojiPages.Words("pig face", List.of("face", "pig"))),
                Map.entry("🥨", new EmojiPages.Words("pretzel", List.of("twisted"))),
                Map.entry("🫛", new EmojiPages.Words("pea pod", List.of("vegetable"))));
        EmojiPages pages = new EmojiPages(groups, List.of(), words, Map.of());

        pages.search("pi");
        check(pages.searching() && pages.group().isEmpty(), "A search must have no group name");
        check(pages.page().equals(List.of("🥧", "🍕", "🪅", "🍍", "🐖", "🐷")),
                "A search must show 6 matches a page, names that start with the query first: " + pages.page());
        check(pages.results() == 7, "A search must count every match: " + pages.results());
        pages.turn(1);
        check(pages.page().equals(List.of("🥟")), "Turning must show the rest of the matches, keywords last");
        pages.jump(1);
        check(pages.page().equals(List.of("🥟")), "A search must have no groups to jump between");

        pages.search("pig");
        check(pages.page().equals(List.of("🐖", "🐷")), "A name that is the query must come first");
        pages.search("pinata");
        check("🪅".equals(pages.top()), "A search must find names with accents");
        pages.search("PIZZA");
        check("🍕".equals(pages.top()), "A search must ignore capitals");
        pages.search("thu up");
        check(pages.page().equals(List.of("👍")), "Each word must start a word of the emoji, in any order");
        pages.search("thumbs ");
        check(pages.page().equals(List.of("👍", "👎")), "A space at the end must not change the matches");
        pages.search("hand");
        check(pages.page().equals(List.of("👋", "👍", "👎")), "A match in the name must come before a keyword match");
        pages.search("happy");
        check(pages.page().equals(List.of("😀", "😂")), "Keyword matches must keep the list order");
        pages.search("zebra");
        check(pages.results() == 0 && pages.top() == null && pages.page().isEmpty(), "A search with no match must show nothing");

        pages.use("😂");
        pages.search("face");
        check(pages.page().get(0).equals("😂"), "A recent emoji that matches must come first");
        pages.search("");
        check(!pages.searching() && pages.group().equals("Faces") && pages.page().get(0).equals("😂"),
                "An empty search must open the groups again, with the recent emoji first");
        pages.search("pi");
        pages.reset();
        check(!pages.searching() && pages.query().isEmpty(), "Reopening must clear the search");
    }

    private static void tones() throws Exception {
        List<EmojiPages.Group> groups = List.of(new EmojiPages.Group("Hands", List.of("👍", "✌️", "😀")));
        Map<String, List<String>> tones = EmojiPages.parseTones(new StringReader(
                "# comment\n👍\t👍🏻 👍🏼 👍🏽 👍🏾 👍🏿\n✌️\t✌🏻 ✌🏼 ✌🏽 ✌🏾 ✌🏿\n"), emoji -> !emoji.equals("✌🏾"));
        check(tones.keySet().equals(java.util.Set.of("👍")), "An emoji the phone cannot show in each tone must keep no tones");
        EmojiPages pages = new EmojiPages(groups, List.of("👍🏽"), Map.of("👍", new EmojiPages.Words("thumbs up", List.of())), tones);
        check(pages.page().equals(List.of("👍", "👍", "✌️", "😀")), "A saved toned emoji must come back without its tone");
        pages.tone(EmojiPages.Tone.MEDIUM_DARK);
        check(pages.page().equals(List.of("👍🏾", "👍🏾", "✌️", "😀")), "The pages must show the chosen tone where there is one");
        pages.search("thumbs");
        check("👍🏾".equals(pages.top()), "The best match must be in the chosen tone");
        check(pages.use("👍🏾").equals(List.of("👍")), "A picked toned emoji must save without its tone, once");
        check(EmojiPages.Tone.of("medium-dark") == EmojiPages.Tone.MEDIUM_DARK && EmojiPages.Tone.of("other") == EmojiPages.Tone.DEFAULT,
                "Tones must read from their saved names");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
