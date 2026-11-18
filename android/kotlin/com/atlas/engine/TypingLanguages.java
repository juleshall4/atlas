package com.atlas.engine;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;

/**
 * The languages that typed suggestions can use. English comes with Atlas. The others are files from the download
 * page, which Atlas keeps only when they match the fingerprint here.
 */
public final class TypingLanguages {
    public static final class Language {
        public final String code, name;
        /** The downloaded file's name, SHA-256 and size, or null for English, which comes with Atlas. */
        final String file, sha256;
        final long size;
        /** English has its own spellings, contractions and look-alikes, and the context model knows only English. */
        public final boolean english;
        /** Dictionary words with capitals, such as "Paris", are suggested. English leaves names to the context model's list. */
        final boolean capitals;
        /** The words of one letter. Other single letters are left out of suggestions. */
        final Set<String> singleLetters;

        Language(String code, String name, String file, String sha256, long size, boolean english, boolean capitals,
                 Set<String> singleLetters) {
            this.code = code; this.name = name; this.file = file; this.sha256 = sha256; this.size = size;
            this.english = english; this.capitals = capitals; this.singleLetters = singleLetters;
        }
    }

    public static final Language ENGLISH = new Language("en", "English", null, null, 0, true, false, Set.of("a", "i"));

    public static final List<Language> ALL = java.util.stream.Stream.concat(
            java.util.stream.Stream.of(ENGLISH), LanguageFiles.ALL.stream()).toList();

    /** The language with this code, or English when there is none. */
    public static Language find(String code) {
        for (Language language : ALL) if (language.code.equals(code)) return language;
        return ENGLISH;
    }

    static File directory(Context context) { return new File(context.getNoBackupFilesDir(), "languages"); }

    /** The compressed word list, for a downloaded language. */
    static File file(Context context, Language language) { return new File(directory(context), language.code + ".combined.gz"); }

    public static boolean installed(Context context, Language language) {
        return language.file == null || file(context, language).length() == language.size;
    }

    /** Keeps a downloaded language file if it is one of the files on the download page, and says which language it is. */
    public static Language add(Context context, InputStream input) throws IOException {
        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not make the languages folder");
        long largest = ALL.stream().mapToLong(language -> language.size).max().orElse(0);
        File staging = File.createTempFile("language-", ".tmp", directory);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (FileOutputStream output = new FileOutputStream(staging)) {
                byte[] buffer = new byte[65536];
                for (int read; (read = input.read(buffer)) > 0;) {
                    total += read;
                    if (total > largest) throw new NotLanguage();
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            String sha256 = hex(digest.digest());
            for (Language language : ALL) {
                if (language.file == null || !language.sha256.equals(sha256) || language.size != total) continue;
                Files.move(staging.toPath(), file(context, language).toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                return language;
            }
            throw new NotLanguage();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException(error);
        } finally {
            staging.delete();
        }
    }

    /** The file is not one of the language files on the download page. */
    public static final class NotLanguage extends IOException {
        NotLanguage() { super("Not an Atlas language file"); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(Character.forDigit((value >> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return result.toString();
    }

    private TypingLanguages() {}
}
