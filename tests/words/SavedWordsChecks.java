package com.atlas.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

public final class SavedWordsChecks {
    public static void main(String[] args) throws Exception {
        int day = 20000;
        LanguageHabits words = new LanguageHabits();
        words.add("Atlas", day);
        check(words.active(day).size() == 1, "An added word must be active immediately");
        check(words.restoreCase("atlas", day).equals("Atlas"), "Keep the entered spelling");
        words.add("Atlas", day);
        check(Arrays.equals(words.spellings(day), new String[] { "Atlas" }), "Adding the same spelling twice must leave one word");
        words.add("ATLAS", day);
        check(Arrays.equals(words.spellings(day), new String[] { "ATLAS" }), "Adding a case variant must update the existing word");
        LanguageHabits imported = new LanguageHabits();
        imported.add("atlas", day);
        words.merge(imported);
        check(Arrays.equals(words.spellings(day), new String[] { "ATLAS" }), "Importing a case variant must keep the local spelling");
        words.regret("atlas");
        words.regret("atlas");
        check(words.active(day).isEmpty(), "The word must be held before testing reactivation");
        words.add("Atlas", day + 28);
        check(words.active(day + 28).size() == 1, "Explicitly adding a held word must reactivate it");
        words.add("O’Brien", day + 28);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        words.write(new DataOutputStream(bytes));
        LanguageHabits restored = new LanguageHabits();
        restored.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        check(Arrays.equals(restored.spellings(day + 28), new String[] { "Atlas", "O'Brien" }),
                "Added spellings and apostrophes must survive a reload");
        check(restored.active(day + 28).size() == 2, "Added words must remain active after a reload");
        for (String invalid : new String[] { "", "a", "two words", "word1", "abcdefghijklmnopqrstuvwxyz" }) {
            try {
                restored.add(invalid, day + 28);
                throw new AssertionError("Accepted invalid word: " + invalid);
            } catch (IllegalArgumentException expected) {
                check(restored.entries().size() == 2, "Invalid input must leave saved words unchanged");
            }
        }
        restored.remove("atlas");
        check(Arrays.equals(restored.spellings(day + 28), new String[] { "O'Brien" }), "Added words must be removable");
        checkLearning(day);
        System.out.println("Saved word checks passed");
    }

    private static void checkLearning(int day) throws Exception {
        LanguageHabits words = new LanguageHabits();
        words.typed("becuase", day, true);
        check(words.spellings(day).length == 0, "A word typed once must not show in the list");
        words.typed("becuase", day, true);
        words.untyped("becuase", day);
        words.untyped("becuase", day);
        check(words.entries().isEmpty(), "A typo that the user deleted must leave no trace");

        for (int i = 0; i < 3; i++) words.typed("Zorbly", day, true);
        check(Arrays.equals(words.spellings(day), new String[] { "Zorbly" }), "A word typed 3 times must show in the list");
        check(words.active(day + 29).size() == 1, "A learned word must stay active for a month");
        check(words.active(day + 30).isEmpty() && words.spellings(day + 30).length == 0,
                "A learned word that is not used for a month must fade");

        words.typed("qwop", day, true);
        words.typed("plim", day + 30, true);
        check(words.entries().stream().noneMatch(entry -> entry.word.equals("qwop")), "A faded candidate must be deleted");

        words.add("Liv", day);
        words.untyped("liv", day);
        check(words.active(day + 3650).stream().anyMatch(entry -> entry.word.equals("liv")), "An added word must never fade");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        words.write(new DataOutputStream(bytes));
        LanguageHabits reloaded = new LanguageHabits();
        reloaded.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        check(Arrays.asList(reloaded.spellings(day + 3650)).contains("Liv"), "An added word must never fade after a reload");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
