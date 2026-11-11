package com.atlas.engine;

/**
 * The keys typed while Sym is on. Letters search the emoji instead of typing, and the query shows only on the strip.
 * Enter inserts the best match. Space and Enter type the query as text when nothing matches, so a Sym pressed in error loses nothing.
 */
public final class EmojiSearch {
    public enum Action {
        /** Not for the search: the key does its usual job. */
        PASS,
        /** The query changed, or stayed the same. */
        SEARCH,
        /** Insert [Outcome#text], the best match. */
        INSERT,
        /** Close the emoji, type [Outcome#text], then let the key do its usual job. */
        TYPE,
        /** Close the emoji. */
        CLOSE,
    }

    public record Outcome(Action action, String text) {
        static Outcome of(Action action) {
            return new Outcome(action, "");
        }
    }

    private final EmojiPages pages;

    public EmojiSearch(EmojiPages pages) {
        this.pages = pages;
    }

    /** A typed character. Letters and digits start a search; after that, apostrophes join it too. */
    public Outcome character(int codePoint) {
        String query = pages.query();
        if (Character.isLetterOrDigit(codePoint) || !query.isEmpty() && (codePoint == '\'' || codePoint == '’')) {
            pages.search(query + Character.toString(codePoint));
            return Outcome.of(Action.SEARCH);
        }
        return Outcome.of(query.isEmpty() ? Action.PASS : Action.SEARCH);
    }

    /** Space joins words such as "thumbs up". */
    public Outcome space() {
        String query = pages.query();
        if (query.isEmpty()) return Outcome.of(Action.PASS);
        if (pages.results() == 0) return type();
        if (!query.endsWith(" ")) pages.search(query + " ");
        return Outcome.of(Action.SEARCH);
    }

    public Outcome enter() {
        if (pages.query().isEmpty()) return Outcome.of(Action.PASS);
        String top = pages.top();
        if (top == null) return type();
        pages.reset();
        return new Outcome(Action.INSERT, top);
    }

    public Outcome backspace() {
        String query = pages.query();
        if (query.isEmpty()) return Outcome.of(Action.CLOSE);
        pages.search(query.substring(0, query.offsetByCodePoints(query.length(), -1)));
        return Outcome.of(Action.SEARCH);
    }

    private Outcome type() {
        String query = pages.query();
        pages.reset();
        return new Outcome(Action.TYPE, query);
    }
}
