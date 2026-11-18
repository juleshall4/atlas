package com.atlas.engine;

/** Keeps only a short token assembled from trusted manual keys. */
public final class TypedWords {
    public record Completed(String spelling, boolean automaticInitial) {}

    private final StringBuilder token = new StringBuilder();
    private boolean overflow;

    public Completed edit(String edit, String beforeCursor, boolean documentStart) {
        if (edit.equals("\b")) {
            if (token.length() > 0) token.deleteCharAt(token.length() - 1);
            return null;
        }
        if (edit.length() == 1 && Character.isWhitespace(edit.charAt(0))) {
            String raw = token.toString();
            int start = beforeCursor == null ? -1 : beforeCursor.length() - raw.length();
            boolean fits = !overflow && !raw.isEmpty() && start >= 0
                    && beforeCursor.regionMatches(true, start, raw, 0, raw.length());
            fits &= start > 0 ? Character.isWhitespace(beforeCursor.charAt(start - 1)) : start == 0 && documentStart;
            clear();
            if (!fits) return null;
            int left = 0, right = raw.length();
            while (left < right && "\"'([{“‘".indexOf(raw.charAt(left)) >= 0) left++;
            while (right > left && ".,!?;:)\u005d}\"'”’".indexOf(raw.charAt(right - 1)) >= 0) right--;
            String spelling = raw.substring(left, right);
            if (SwipeWords.normalise(spelling).isEmpty()) return null;
            int visible = beforeCursor.codePointAt(start + left);
            boolean automaticInitial = Character.isLowerCase(spelling.codePointAt(0))
                    && (Character.isUpperCase(visible) || Character.isTitleCase(visible));
            return new Completed(spelling, automaticInitial);
        }
        if (token.length() + edit.length() > 64) overflow = true;
        else if (!overflow) token.append(edit);
        return null;
    }

    public void clear() { token.setLength(0); overflow = false; }
}
