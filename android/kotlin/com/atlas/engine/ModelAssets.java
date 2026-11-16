package com.atlas.engine;

import android.content.res.AssetManager;
import android.util.Log;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Copies the bundled models out of the APK, checking each against the manifest. A full check reads every file, so it
 * runs only when the bundled models change, or after {@link #forget}. Other starts check only the sizes.
 */
final class ModelAssets {
    private static final String VERIFIED = "verified";

    static void install(AssetManager assets, Path directory) throws IOException {
        String manifest;
        try (InputStream input = assets.open("swipe/manifest.sha256")) {
            manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String[] lines = manifest.split("\n");
        if (!"physical-swipe-models-v1".equals(lines[0])) throw new IOException("Unsupported swipe model manifest");
        List<String[]> entries = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            String[] entry = lines[i].split(" ", 3);
            if (entry.length != 3 || !entry[0].matches("[a-f0-9]{64}")
                    || !entry[1].matches("[0-9]{1,12}") || !entry[2].matches("[a-zA-Z0-9_./-]+")
                    || entry[2].startsWith("/") || entry[2].contains("..") || !paths.add(entry[2]))
                throw new IOException("Invalid swipe model manifest");
            entries.add(entry);
        }
        if (entries.isEmpty()) throw new IOException("Empty swipe model manifest");
        Path stamp = directory.resolve(VERIFIED);
        if (Files.isRegularFile(stamp) && manifest.equals(new String(Files.readAllBytes(stamp), StandardCharsets.UTF_8))
                && entries.stream().allMatch(entry -> sized(directory.resolve(entry[2]), Long.parseLong(entry[1])))) {
            Log.d("TouchpadSwipe", "Swipe model assets already verified");
            return;
        }
        int repaired = 0;
        for (String[] entry : entries) {
            long size = Long.parseLong(entry[1]);
            Path target = directory.resolve(entry[2]);
            if (matches(target, size, entry[0])) continue;
            Files.createDirectories(target.getParent());
            Path staging = Files.createTempFile(target.getParent(), "swipe-model-", ".tmp");
            try {
                try (InputStream input = assets.open("swipe/" + entry[2]);
                     FileOutputStream output = new FileOutputStream(staging.toFile())) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.getFD().sync();
                }
                if (!matches(staging, size, entry[0])) throw new IOException("Bundled swipe model failed integrity check");
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                repaired++;
            } finally { Files.deleteIfExists(staging); }
        }
        // Files that earlier versions bundled would otherwise stay on the phone for good.
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator)
                if (!paths.contains(directory.relativize(file).toString())) Files.delete(file);
        }
        Path staging = Files.createTempFile(directory, "verified-", ".tmp");
        try {
            Files.write(staging, manifest.getBytes(StandardCharsets.UTF_8));
            Files.move(staging, stamp, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staging); }
        Log.d("TouchpadSwipe", "Verified " + entries.size() + " swipe model assets; repaired " + repaired);
    }

    /** The next install checks every file in full. */
    static void forget(Path directory) throws IOException { Files.deleteIfExists(directory.resolve(VERIFIED)); }

    private static boolean sized(Path file, long size) {
        try { return Files.isRegularFile(file) && Files.size(file) == size; }
        catch (IOException error) { return false; }
    }

    private static boolean matches(Path file, long size, String expected) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) != size) return false;
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IOException("SHA-256 unavailable", error); }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest()) actual.append(Character.forDigit((value & 255) >>> 4, 16))
                .append(Character.forDigit(value & 15, 16));
        return expected.contentEquals(actual);
    }
}
