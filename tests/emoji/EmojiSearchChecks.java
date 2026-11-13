package com.atlas.engine;

import java.util.List;
import java.util.Map;

public final class EmojiSearchChecks {
    public static void main(String[] args) {
        EmojiPages pages = new EmojiPages(
                List.of(new EmojiPages.Group("Hands", List.of("👍", "👋"))),
                List.of(),
                Map.of("👍", new EmojiPages.Words("thumbs up", List.of("good")),
                        "👋", new EmojiPages.Words("waving hand", List.of("hello", "wave"))),
                Map.of("👍", List.of("👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿")));
        EmojiSearch search = new EmojiSearch(pages);

        check(search.space().action() == EmojiSearch.Action.PASS, "Space with no query must type a space as usual");
        check(search.enter().action() == EmojiSearch.Action.PASS, "Enter with no query must do its usual job");
        check(search.character(',').action() == EmojiSearch.Action.PASS, "Punctuation must not start a search");
        check(search.backspace().action() == EmojiSearch.Action.CLOSE, "Backspace with no query must close the emoji");

        type(search, "thumbs");
        check(pages.query().equals("thumbs") && pages.results() == 1, "Letters must go to the query");
        check(search.space().action() == EmojiSearch.Action.SEARCH && pages.query().equals("thumbs "), "Space must join words in the query");
        search.space();
        check(pages.query().equals("thumbs "), "A second space must not add to the query");
        check(search.character(',').action() == EmojiSearch.Action.SEARCH && pages.query().equals("thumbs "),
                "Punctuation during a search must do nothing");
        type(search, "up");
        pages.tone(EmojiPages.Tone.LIGHT);
        check(search.enter().equals(new EmojiSearch.Outcome(EmojiSearch.Action.INSERT, "👍🏻")),
                "Enter must insert the best match in the chosen tone");
        check(!pages.searching(), "Inserting must clear the query for the next search");

        type(search, "wav");
        check(search.backspace().action() == EmojiSearch.Action.SEARCH && pages.query().equals("wa"), "Backspace must remove the last letter");
        search.backspace();
        search.backspace();
        check(!pages.searching() && search.backspace().action() == EmojiSearch.Action.CLOSE,
                "Backspace on an emptied query must then close the emoji");

        type(search, "hello");
        check(search.space().action() == EmojiSearch.Action.SEARCH, "Space must stay in a query that still matches");
        type(search, "world");
        check(pages.results() == 0, "The test query must match nothing");
        check(search.space().equals(new EmojiSearch.Outcome(EmojiSearch.Action.TYPE, "hello world")),
                "Space with no match must type the query as text");
        check(!pages.searching(), "Typing the query must clear it");

        type(search, "don't");
        check(pages.query().equals("don't"), "An apostrophe must join a started query");
        check(search.enter().equals(new EmojiSearch.Outcome(EmojiSearch.Action.TYPE, "don't")),
                "Enter with no match must type the query as text");

        System.out.println("Emoji search checks passed");
    }

    private static void type(EmojiSearch search, String text) {
        text.codePoints().forEach(search::character);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
