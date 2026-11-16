package com.atlas.engine;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import org.futo.ml.inference.SwipeDecoder;
import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class SwipeTyping {
    private static volatile SwipeTyping instance;
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger pendingExamples = new AtomicInteger();
    private SwipeRecognizer recogniser;
    private volatile TypedPredictions typedPredictions;
    private volatile String loadError;
    private volatile String readiness = "loading";
    private int loadingRetries;
    private final InferenceTiming recognitionTiming = new InferenceTiming("Recognition");
    private final InferenceTiming completionTiming = new InferenceTiming("Completion");
    private LanguageHabits dictionary = new LanguageHabits();
    private PersonalizationStats accuracy = new PersonalizationStats();
    private AtomicFile dictionaryFile;
    private final AtomicFile accuracyFile;
    private LearningConfigs configs;
    private volatile boolean learningEnabled;
    private volatile boolean wordsAvailable;
    private volatile String wordsSaveError = "";
    private volatile long learningGeneration, accuracyEpoch;
    /** The last word that {@link #typed} counted. Only the worker uses it. */
    private String lastTyped;
    private volatile String learningStatus = "Loading personal dictionary…", accuracyStatus = "Loading accuracy stats…";
    private long configuredRevision = -1;
    private int configuredDay = -1, activeCount;
    private volatile Words wordView = new Words(new String[0], new String[0], new String[0]);
    private volatile BlockedWords blockedWords;

    public static final class Words {
        public final String[] words, descriptions, swipeStatuses;
        Words(String[] words, String[] descriptions, String[] swipeStatuses) {
            this.words = words; this.descriptions = descriptions; this.swipeStatuses = swipeStatuses;
        }
    }
    public interface Callback {
        void result(String[] words, long elapsed, String error);
        default void result(String[] words, long elapsed, String error, Prediction prediction) { result(words, elapsed, error); }
    }
    public interface OperationCallback { void result(String error); }
    public static final class Prediction {
        final String firstWord;
        final EnglishSpelling.Variant variant;
        final boolean personalized;
        final long epoch, generation;
        final SwipeDecoder.Result[] results;
        Prediction(String firstWord, EnglishSpelling.Variant variant, boolean personalized, long epoch,
                   long generation, SwipeDecoder.Result[] results) {
            this.firstWord = firstWord; this.variant = variant; this.personalized = personalized;
            this.epoch = epoch; this.generation = generation; this.results = results;
        }
    }
    public static synchronized SwipeTyping get(Context context) {
        if (instance == null) instance = new SwipeTyping(context.getApplicationContext());
        return instance;
    }
    private SwipeTyping(Context context) {
        this.context = context;
        blockedWords = new BlockedWords(context.getSharedPreferences("blocked_words", 0).getStringSet("words", java.util.Set.of()));
        Calibration.init(context);
        learningEnabled = context.getSharedPreferences("word_learning", 0).getBoolean("enabled",
                context.getSharedPreferences("swipe_calibration", 0).getBoolean("enabled", true));
        context.getSharedPreferences("word_learning", 0).edit().remove("gesture_enabled").apply();
        accuracyFile = new AtomicFile(new File(context.getNoBackupFilesDir(), "word-accuracy-v1"));
        worker.execute(() -> {
            loadHabits();
            try {
                try (DataInputStream input = new DataInputStream(accuracyFile.openRead())) { accuracy.read(input); }
                catch (IOException | RuntimeException ignored) { accuracy = new PersonalizationStats(); }
                // Coordinate calibration and its statistics are deliberately not migrated into word learning.
                new AtomicFile(new File(context.getNoBackupFilesDir(), "swipe-calibration-v1")).delete();
                new AtomicFile(new File(context.getNoBackupFilesDir(), "swipe-accuracy-v1")).delete();
                loadEngine();
            } catch (IOException | RuntimeException | LinkageError error) {
                unavailable(error);
            }
        });
    }

    private void loadEngine() throws IOException {
        long began = SystemClock.uptimeMillis();
        if (recogniser != null) recogniser.close();
        recogniser = null; typedPredictions = null;
        configuredRevision = -1; configuredDay = -1; activeCount = 0;
        recogniser = new SwipeRecognizer(context);
        // The copies on disk are already checked and need no unzipping, unlike the ones in the APK.
        File models = SwipeRecognizer.models(context);
        File vocabularyFile = new File(models, "hungry_jellyfish/vocab.txt");
        try (Reader vocabulary = new InputStreamReader(new FileInputStream(vocabularyFile), "UTF-8")) {
            ProperNames.load(vocabulary);
        }
        try (Reader vocabulary = new InputStreamReader(new FileInputStream(vocabularyFile), "UTF-8");
             Reader dictionary = new InputStreamReader(new FileInputStream(new File(models, "english.combined")), "UTF-8")) {
            typedPredictions = new TypedPredictions(vocabulary, dictionary, word -> recogniser.frequency(word));
        }
        syncDictionary(); publish();
        loadError = null; readiness = "ready";
        Log.d("TouchpadSwipe", "Swipe engine ready in " + (SystemClock.uptimeMillis() - began) + " ms");
    }

    private void unavailable(Throwable error) {
        SwipeRecognizer failed = recogniser;
        recogniser = null; typedPredictions = null;
        loadError = "Swipe typing unavailable. Retry loading in settings.";
        readiness = "unavailable";
        if (failed != null) {
            try { failed.close(); }
            catch (RuntimeException | LinkageError closeError) { error.addSuppressed(closeError); }
        }
        Log.e("TouchpadSwipe", "Could not load FUTO Swipe", error);
    }

    public String readiness() { return readiness; }
    public String availabilityError() { return loadError; }

    /** Explicit recovery is bounded; recognition never reloads native models implicitly. */
    public void retryLoading(OperationCallback callback) {
        worker.execute(() -> {
            String error = null;
            if (!"ready".equals(readiness)) {
                if (loadingRetries >= 2) error = "Swipe loading retry limit reached. Restart Atlas to try again.";
                else {
                    loadingRetries++; readiness = "loading";
                    try { SwipeRecognizer.forgetModels(context); loadEngine(); }
                    catch (IOException | RuntimeException | LinkageError failure) { unavailable(failure); error = loadError; }
                }
            }
            operationResult(callback, error);
        });
    }

    private void operationResult(OperationCallback callback, String error) {
        main.post(() -> callback.result(error));
    }

    private LearningConfigs configs() throws IOException {
        if (configs == null) {
            configs = new LearningConfigs(new File(context.getFilesDir(), "config"), context.getNoBackupFilesDir());
            dictionaryFile = configs.words;
            try { dictionary = configs.loadWords(); wordsAvailable = true; wordsSaveError = ""; }
            catch (IOException | RuntimeException error) { Log.w("TouchpadSwipe", "Saved words unavailable", error); }
            configuredRevision = -1;
            publish();
        }
        return configs;
    }

    private void loadHabits() {
        try { configs(); }
        catch (IOException | RuntimeException error) {
            publish();
            Log.w("TouchpadSwipe", "Habits storage unavailable", error);
        }
    }
    public void whenReady(Callback callback) { worker.execute(() -> main.post(() -> callback.result(new String[0], 0, loadError))); }
    public void recognise(Swipe gesture, String text, Callback callback) { recognise(gesture, text, true, callback); }
    public void recognise(Swipe gesture, String text, boolean personalise, Callback callback) {
        final String[] contextWords = SentenceContext.words(text);
        final EnglishSpelling.Variant variant = TypingSettings.spelling(context);
        final long epoch = accuracyEpoch, generation = learningGeneration;
        InferenceTiming.Request timing = new InferenceTiming.Request();
        worker.execute(() -> {
            timing.start();
            long began = SystemClock.uptimeMillis();
            try {
                if (recogniser == null) throw new IllegalStateException(loadError);
                syncDictionary();
                boolean personalized = learningEnabled && wordsAvailable && personalise && activeCount > 0;
                SwipeDecoder.Result[] decoded = recogniser.recognise(gesture, contextWords, personalized,
                        word -> wordAllowed(rendered(word, variant)));
                String[] words = blockedWords.filter(EnglishSpelling.candidates(Arrays.stream(decoded).map(result -> result.word).toArray(String[]::new), variant));
                if (personalized) for (int i = 0; i < words.length; i++) words[i] = dictionary.restoreCase(words[i], configuredDay);
                Prediction prediction = new Prediction(words.length == 0 ? "" : words[0], variant,
                        personalized, epoch, generation, decoded);
                long elapsed = SystemClock.uptimeMillis() - began;
                timing.finish();
                main.post(() -> {
                    recognitionTiming.record(timing, false, false, true);
                    callback.result(blockedWords.filter(words), elapsed, null, prediction);
                });
            } catch (RuntimeException | LinkageError error) {
                if (recogniser != null && !recogniser.isOpen()) unavailable(error);
                timing.finish();
                main.post(() -> {
                    recognitionTiming.record(timing, true, false, true);
                    callback.result(new String[0], 0, "Swipe recognition failed");
                });
            }
        });
    }
    /** Offers the look-alike that fits the next word better, such as "were" for "we're you", or null. */
    public void lookAlike(String before, String word, String next, boolean typed, java.util.function.Consumer<String> result) {
        final EnglishSpelling.Variant variant = TypingSettings.spelling(context);
        worker.execute(() -> {
            String replacement = null;
            try {
                String found = recogniser == null ? null : recogniser.lookAlike(SentenceContext.words(before), word, next.replace('\u2019', '\''), typed);
                if (found != null && wordAllowed(rendered(found, variant))) replacement = rendered(found, variant);
            } catch (RuntimeException | LinkageError error) {
                if (recogniser != null && !recogniser.isOpen()) unavailable(error);
            }
            final String offered = replacement;
            main.post(() -> result.accept(offered));
        });
    }

    public void complete(String before, AtomicBoolean cancelled, boolean sentenceCapitalisation, Callback callback) {
        final EnglishSpelling.Variant variant = TypingSettings.spelling(context);
        final boolean autoCapitalise = sentenceCapitalisation && TypingSettings.autoCapitalise(context);
        InferenceTiming.Request timing = new InferenceTiming.Request();
        worker.execute(() -> {
            timing.start();
            if (cancelled.get()) {
                timing.finish(); completionTiming.record(timing, false, true, false); return;
            }
            String[] result;
            String failure = null;
            try {
                if (typedPredictions == null) throw new IllegalStateException(loadError);
                final String prefix = TypedPredictions.prefix(before);
                final String contextText = before.substring(0, before.length() - prefix.length());
                int day = today();
                List<LanguageHabits.Entry> personal = learningEnabled && wordsAvailable ? dictionary.active(day) : List.of();
                if (cancelled.get()) {
                    timing.finish(); completionTiming.record(timing, false, true, false); return;
                }
                Map<String, Double> likely = recogniser == null ? Map.of() : recogniser.nextWords(SentenceContext.words(contextText));
                result = blockedWords.filter(EnglishSpelling.candidates(typedPredictions.suggest(prefix, personal, day,
                        word -> blockedWords.entries().isEmpty() || wordAllowed(rendered(word, variant)), likely), variant));
                boolean upper = prefix.length() > 1 && prefix.equals(prefix.toUpperCase(Locale.UK));
                // A lowercase start of a sentence was typed with Shift, so suggestions keep it.
                boolean sentenceCase = autoCapitalise && (prefix.isEmpty() || !Character.isLowerCase(prefix.charAt(0))
                        || !SentenceCase.startsSentence(contextText));
                for (int i = 0; i < result.length; i++) {
                    String word = learningEnabled && wordsAvailable ? dictionary.restoreCase(result[i], today()) : result[i];
                    result[i] = upper ? word.toUpperCase(Locale.UK) : capitalise(word, contextText, 0,
                            !prefix.isEmpty() && Character.isUpperCase(prefix.charAt(0)), sentenceCase);
                }
            } catch (RuntimeException | LinkageError error) {
                if (recogniser != null && !recogniser.isOpen()) unavailable(error);
                result = new String[0]; failure = "Word completion failed";
            }
            String[] words = result;
            String error = failure;
            timing.finish();
            main.post(() -> {
                boolean isCancelled = cancelled.get();
                completionTiming.record(timing, error != null, isCancelled, true);
                if (!isCancelled) callback.result(blockedWords.filter(words), timing.finished - timing.started, error);
            });
        });
    }

    public boolean learningEnabled() { return learningEnabled; }
    boolean feedbackEnabled() { return learningEnabled && wordsAvailable; }
    public long learningGeneration() { return learningGeneration; }
    public String learningStatus() { return (learningEnabled ? "Learning on · " : "Language habits paused · ") + learningStatus + wordsSaveError; }
    String accuracyStats() { return accuracyStatus; }
    public Words learnedWords() { return wordView; }
    public String[] blockedWords() { return blockedWords.words(); }
    public boolean wordAllowed(String word) { return blockedWords.allowed(word); }

    /** Whether a typed word is complete, so the next suggestions should follow it instead of correcting it. */
    public boolean knownWord(String word) {
        TypedPredictions predictions = typedPredictions;
        if (predictions != null && predictions.contains(word)) return true;
        return Arrays.stream(wordView.words).anyMatch(word::equalsIgnoreCase);
    }

    public void setWordBlocked(String word, boolean blocked, OperationCallback callback) {
        worker.execute(() -> {
            String failure = null;
            boolean persisted = false;
            try {
                BlockedWords replacement = blocked ? blockedWords.add(word) : blockedWords.remove(word);
                if (!context.getSharedPreferences("blocked_words", 0).edit().putStringSet("words", replacement.entries()).commit())
                    throw new IOException("Could not save blocked words");
                blockedWords = replacement;
                learningGeneration++;
                configuredRevision = -1;
                persisted = true;
                syncDictionary();
            } catch (IllegalArgumentException error) {
                failure = error.getMessage();
            } catch (IOException | RuntimeException | LinkageError error) {
                failure = persisted ? "Blocked words were saved, but the swipe dictionary could not be refreshed."
                        : "Could not save blocked words. Try again.";
            }
            operationResult(callback, failure);
        });
    }
    public void learningEnabled(boolean enabled) {
        learningEnabled = enabled; learningGeneration++;
        context.getSharedPreferences("word_learning", 0).edit().putBoolean("enabled", enabled).apply();
    }
    void resetAccuracyStats() {
        accuracyEpoch++;
        worker.execute(() -> { accuracy = new PersonalizationStats(); accuracyFile.delete(); accuracyStatus = accuracy.summary(); });
    }
    public void clearDictionary(OperationCallback callback) {
        learningGeneration++;
        worker.execute(() -> {
            boolean persisted = false;
            String failure = null;
            try {
                LanguageHabits cleared = new LanguageHabits();
                configs().replaceWords(cleared);
                dictionary = cleared; wordsAvailable = true; wordsSaveError = ""; configuredRevision = -1;
                persisted = true;
                refreshDictionary();
            } catch (IOException | RuntimeException | LinkageError error) {
                failure = persisted ? "Saved words were cleared, but the swipe dictionary could not be refreshed."
                        : "Could not reset saved words. Existing words were kept.";
                wordsSaveError = persisted ? " · Swipe dictionary refresh failed" : " · Could not reset saved words";
                if (recogniser != null && !recogniser.isOpen()) unavailable(error);
            }
            try { publish(); } finally { operationResult(callback, failure); }
        });
    }
    public void addWord(String word, OperationCallback callback) {
        learningGeneration++;
        worker.execute(() -> {
            boolean persisted = false;
            String failure = null;
            try {
                configs();
                if (!wordsAvailable) throw new IOException("Saved words unavailable");
                LanguageHabits replacement = dictionary.copy();
                if (blockedWords.contains(word)) throw new IllegalArgumentException("This word is blocked. Remove it from Blocked words first.");
                replacement.add(word.trim(), today());
                configs.saveWords(replacement);
                dictionary = replacement; configuredRevision = -1; wordsSaveError = "";
                persisted = true;
                refreshDictionary();
            } catch (IllegalArgumentException error) {
                failure = error.getMessage();
            } catch (IOException | RuntimeException | LinkageError error) {
                failure = persisted ? "The word was saved, but the swipe dictionary could not be refreshed."
                        : "Could not save the word. Try again.";
                wordsSaveError = persisted ? " · Swipe dictionary refresh failed" : " · Could not save word";
                if (recogniser != null && !recogniser.isOpen()) unavailable(error);
            }
            try { publish(); } finally { operationResult(callback, failure); }
        });
    }
    public void removeWord(String word, OperationCallback callback) {
        learningGeneration++;
        worker.execute(() -> {
            boolean persisted = false;
            String failure = null;
            try {
                configs();
                if (!wordsAvailable) throw new IOException("Saved words unavailable");
                String normalised = SwipeWords.normalise(word);
                if (normalised.isEmpty()) throw new IllegalArgumentException("Invalid saved word");
                LanguageHabits replacement = dictionary.copy();
                replacement.remove(normalised);
                configs.saveWords(replacement);
                dictionary = replacement; configuredRevision = -1; wordsSaveError = "";
                persisted = true;
                refreshDictionary();
            } catch (IOException | RuntimeException | LinkageError error) {
                failure = persisted ? "The word was removed, but the swipe dictionary could not be refreshed."
                        : "Could not remove the word. Existing words were kept.";
                wordsSaveError = persisted ? " · Swipe dictionary refresh failed" : " · Could not remove saved word";
                if (recogniser != null && !recogniser.isOpen()) unavailable(error);
            }
            try { publish(); } finally { operationResult(callback, failure); }
        });
    }

    public void exportConfig(Uri destination, OperationCallback callback) {
        worker.execute(() -> {
            String failure = null;
            try {
                if (!wordsAvailable || configs().hasPendingImport())
                    throw new IOException("Learning data unavailable");
                var backup = LearningConfigs.export(dictionary);
                try (OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new IOException("Could not open the export file");
                    LearningConfigs.writeJson(output, backup);
                }
            } catch (IOException | RuntimeException error) {
                Log.w("TouchpadSwipe", "Could not export learning", error);
                failure = "Could not export learning. Restart Atlas and try again.";
            }
            operationResult(callback, failure);
        });
    }

    public void importConfig(Uri source, OperationCallback callback) {
        worker.execute(() -> {
            LanguageHabits imported;
            try (InputStream input = context.getContentResolver().openInputStream(source)) {
                if (input == null) throw new IOException("Could not open the import file");
                imported = dictionary.copy();
                imported.merge(LearningConfigs.readExport(input));
            } catch (IOException | RuntimeException error) {
                Log.w("TouchpadSwipe", "Could not read learning backup", error);
                operationResult(callback, "Could not read the backup. Choose a valid Atlas backup file.");
                return;
            }
            try {
                configs().importWords(imported);
            } catch (IOException | RuntimeException error) {
                Log.w("TouchpadSwipe", "Could not save learning backup", error);
                operationResult(callback, "Could not finish importing. Restart Atlas to recover your learning data.");
                return;
            }
            learningGeneration++;
            dictionary = imported;
            wordsAvailable = true;
            wordsSaveError = ""; configuredRevision = -1;
            String failure = null;
            try {
                syncDictionary();
            } catch (RuntimeException | LinkageError error) {
                Log.w("TouchpadSwipe", "Could not refresh the imported dictionary", error);
                failure = "Learning was imported. Restart Atlas to use the imported words.";
            }
            publish();
            operationResult(callback, failure);
        });
    }

    /** Whether the built-in dictionary already has this word, in either spelling. */
    private boolean standard(String word) {
        String letters = SwipeWords.letters(word);
        return recogniser.frequency(letters) > 0
                || recogniser.frequency(EnglishSpelling.convert(letters, EnglishSpelling.Variant.BRITISH)) > 0
                || recogniser.frequency(EnglishSpelling.convert(letters, EnglishSpelling.Variant.AMERICAN)) > 0;
    }
    private void refreshDictionary() {
        if (recogniser == null) throw new IllegalStateException("Swipe typing unavailable");
        syncDictionary();
    }
    static int today() { return (int) (System.currentTimeMillis() / 86400000L); }
    private void syncDictionary() {
        if (recogniser == null || (configuredRevision == dictionary.revision && configuredDay == today())) return;
        List<LanguageHabits.Entry> active = wordsAvailable ? dictionary.active(today()).stream()
                .filter(entry -> wordAllowed(entry.word)).toList() : java.util.Collections.emptyList();
        String[] words = active.stream().map(entry -> entry.word).toArray(String[]::new);
        float[] frequencies = new float[active.size()];
        for (int i = 0; i < frequencies.length; i++) frequencies[i] = active.get(i).frequency(recogniser.frequency(words[i]), today());
        try { recogniser.personalWords(words, frequencies); }
        catch (RuntimeException | LinkageError error) {
            if (!recogniser.isOpen()) unavailable(error);
            throw error;
        }
        activeCount = active.size(); configuredRevision = dictionary.revision; configuredDay = today();
        publish();
    }
    private static String rendered(String word, EnglishSpelling.Variant variant) { return EnglishSpelling.convert(SwipeSpelling.restore(word), variant); }
    private void publish() {
        String importWarning = configs == null ? "" : configs.importWarning();
        learningStatus = (wordsAvailable ? dictionary.status(today()) : "Saved words unavailable; existing files preserved") + importWarning;
        int day = today();
        wordView = new Words(dictionary.spellings(day), dictionary.descriptions(day), dictionary.swipeStatuses(day));
        accuracyStatus = accuracy.summary();
    }

    public void typed(String intended, long generation) {
        typed(intended, generation, true);
    }

    public void typed(String intended, long generation, boolean rememberCase) {
        String word = SwipeWords.normalise(intended);
        if (!learningEnabled || !wordsAvailable || generation != learningGeneration || word.isEmpty()) return;
        worker.execute(() -> {
            if (!learningEnabled || !wordsAvailable || generation != learningGeneration || recogniser == null) return;
            if (standard(word) || !wordAllowed(word)) return;
            long revision = dictionary.revision;
            dictionary.typed(intended, today(), rememberCase);
            lastTyped = revision == dictionary.revision ? null : word;
            if (lastTyped == null) return;
            try { syncDictionary(); }
            catch (RuntimeException | LinkageError error) {
                if (recogniser != null && !recogniser.isOpen()) unavailable(error);
                Log.w("TouchpadSwipe", "Could not refresh learned words");
            }
            saveDictionary(); publish();
        });
    }
    /** The user deleted the word they just typed, so that use was a mistake. */
    public void untyped(String intended, long generation) {
        String word = SwipeWords.normalise(intended);
        if (word.isEmpty()) return;
        worker.execute(() -> {
            // Only a use that was counted can be taken back.
            if (!word.equals(lastTyped)) return;
            lastTyped = null;
            if (!learningEnabled || !wordsAvailable || generation != learningGeneration || recogniser == null) return;
            long revision = dictionary.revision;
            dictionary.untyped(word, today());
            try { saveLearningChanges(revision); }
            catch (RuntimeException | LinkageError error) {
                if (recogniser != null && !recogniser.isOpen()) unavailable(error);
                Log.w("TouchpadSwipe", "Could not refresh learned words");
            }
        });
    }

    public void confirm(Swipe gesture, String text, String intended, long generation, Prediction prediction, boolean candidate) {
        feedback(gesture, text, intended, generation, prediction, candidate ? LearningEvidence.CHOICE : LearningEvidence.CORRECTION);
    }

    public void retained(Swipe gesture, String text, String intended, long generation, Prediction prediction) {
        feedback(gesture, text, intended, generation, prediction, LearningEvidence.KEPT);
    }

    private void feedback(Swipe gesture, String text, String intended, long generation, Prediction prediction, LearningEvidence evidence) {
        String word = SwipeWords.normalise(intended);
        if (!feedbackEnabled() || generation != learningGeneration || word.isEmpty() || prediction == null) return;
        if (evidence.replacement && !WordSimilarity.replacement(prediction.firstWord, word)) return;
        if (pendingExamples.incrementAndGet() > 4) { pendingExamples.decrementAndGet(); return; }
        final String[] contextWords = SentenceContext.words(text);
        worker.execute(() -> {
            try {
                if (!feedbackEnabled() || generation != learningGeneration || recogniser == null || prediction.generation != generation || !wordAllowed(word)) return;
                long revision = dictionary.revision;
                boolean direct = evidence == LearningEvidence.CHOICE || evidence == LearningEvidence.CORRECTION;
                if (evidence == LearningEvidence.KEPT) {
                    if (!PersonalizationStats.sameWord(prediction.firstWord, word)) return;
                    dictionary.retained(word, today());
                    saveLearningChanges(revision); return;
                }
                SwipeDecoder.Result[] baseline = prediction.personalized ? recogniser.recognise(gesture, contextWords, false) : prediction.results;
                String first = baseline.length == 0 ? "" : rendered(baseline[0].word, prediction.variant);
                boolean standardCorrect = PersonalizationStats.sameWord(first, intended);
                boolean personalCorrect = PersonalizationStats.sameWord(prediction.firstWord, intended);
                if (direct && prediction.epoch == accuracyEpoch) {
                    accuracy.record(standardCorrect, personalCorrect, prediction.personalized, evidence == LearningEvidence.CHOICE);
                    accuracyStatus = accuracy.summary(); saveAccuracy();
                }
                SwipeDecoder.Result match = null;
                for (SwipeDecoder.Result result : prediction.results)
                    if (PersonalizationStats.sameWord(rendered(result.word, prediction.variant), intended)) match = result;
                boolean fits = standardCorrect || match != null || (baseline.length > 0
                        && recogniser.fits(gesture, contextWords, word, baseline[0]));
                if (!fits) return;
                if (prediction.personalized && prediction.results.length > 0) {
                    String previous = rendered(prediction.results[0].word, prediction.variant);
                    if (!PersonalizationStats.sameWord(previous, word) && dictionary.active(today()).stream().anyMatch(entry -> entry.word.equals(SwipeWords.normalise(previous))))
                        dictionary.regret(previous);
                }
                if (!direct) dictionary.retained(word, today());
                else if (!standard(word)) dictionary.confirm(word, today());
                saveLearningChanges(revision);
            } catch (RuntimeException | LinkageError error) {
                if (recogniser != null && !recogniser.isOpen()) unavailable(error);
                Log.w("TouchpadSwipe", "Learning feedback skipped");
            }
            finally { pendingExamples.decrementAndGet(); }
        });
    }
    private void saveLearningChanges(long revision) {
        if (revision != dictionary.revision) { syncDictionary(); saveDictionary(); }
        publish();
    }

    private void saveAccuracy() {
        FileOutputStream stream = null;
        try {
            stream = accuracyFile.startWrite(); DataOutputStream output = new DataOutputStream(stream);
            accuracy.write(output); output.flush(); accuracyFile.finishWrite(stream);
        } catch (IOException error) {
            if (stream != null) accuracyFile.failWrite(stream);
            accuracyStatus = accuracy.summary() + "\nAccuracy stats could not be saved.";
        }
    }
    private void saveDictionary() {
        if (!wordsAvailable) return;
        try {
            if (configs == null) throw new IOException("Config storage unavailable");
            configs.saveWords(dictionary); wordsSaveError = "";
        } catch (IOException error) { wordsSaveError = " · Could not save words"; }
    }

    public static String respell(String word) { return SwipeSpelling.respell(word); }

    public static String capitalise(String text, String before) { return SentenceCase.capitalise(text, before); }

    public static String uncapitalise(String text, String before) { return SentenceCase.uncapitalise(text, before); }

    public static boolean sentenceCapital(String word, String before) { return SentenceCase.sentenceCapital(word, before); }

    public static String capitalise(String str, String str2, int i, boolean z, boolean autoCapitalise) {
        if ((1048576 & i) != 0) {
            return str.toUpperCase(Locale.UK);
        }
        if ((i & 193) != 0 || z) {
            if (str.isEmpty()) {
                return str;
            }
            return str.substring(0, 1).toUpperCase(Locale.UK) + str.substring(1);
        }
        return autoCapitalise ? capitalise(str, str2) : str;
    }

    public static String prefix(String str) {
        if (str.isEmpty()) {
            return "";
        }
        char cCharAt = str.charAt(str.length() - 1);
        return (Character.isLetterOrDigit(cCharAt) || ".,!?;:)]".indexOf(cCharAt) >= 0) ? " " : "";
    }
}
