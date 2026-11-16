package com.atlas.engine;

import android.content.Context;
import com.atlas.keyboard.Layout;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class SwipeRecognizer implements AutoCloseable {
    private static final float MAX_CONTEXT_SCORE = 1.5f;
    private static final float MAX_SENTENCE_CONTEXT_SCORE = 4f;
    private static final float MAX_PATH_SCORE_GAP = 3f;
    private static final int NEXT_WORD_CANDIDATES = 3000;
    // Stands in for the start of a sentence, which the context model would otherwise not score at all.
    private static final String[] SENTENCE_START = {"so"};
    private static final double LOOK_ALIKE_NEXT_WORD_EVIDENCE = 3;
    // The model mostly saw "id" as "I'd" without its apostrophe, so "id rather" looks nearly as usual as "I'd rather".
    // The real word "id" is a noun, and the next word always speaks against "I'd" then, as in "id card".
    private static final Set<String> CASUAL_LOOK_ALIKES = Set.of("i'd");
    // A typed word was not chosen from the earlier words, so they must agree with the next word strongly.
    // A missed change costs little; a wrong one changes what the user typed, so this stays far from real words.
    private static final double TYPED_LOOK_ALIKE_EVIDENCE = 5;
    private static final int LOOK_ALIKE_CANDIDATES = 8000;
    private static final int CANDIDATES = 16;
    // The engine cuts its list after counting context, so a saved word needs a deeper search to stay in it.
    private static final int SAVED_WORD_CANDIDATES = 48;
    // Rarer double-letter words are mostly names and typos, which then take the swipes of common words.
    static final float DOUBLE_LETTER_FLOOR = 100;
    private org.futo.ml.inference.SwipeDecoder decoder;
    private org.futo.ml.inference.NativeDictionary base, personal, contractions, doubleLetters;
    private Set<String> learnedWords = Set.of();
    private org.futo.ml.inference.SwipeDecoder.Result[] rawResults = new org.futo.ml.inference.SwipeDecoder.Result[0];
    private String[] nextWordsContext;
    private Map<String, Double> nextWords;

    public org.futo.ml.inference.SwipeDecoder.Result[] rawResults() { return rawResults; }
    public float contextWeight() { return decoder.contextWeight(); }

    public SwipeRecognizer(Context context) throws IOException { this(installedModels(context)); }

    private static File installedModels(Context context) throws IOException {
        File file = new File(context.getNoBackupFilesDir(), "swipe-models-v1");
        ModelAssets.install(context.getAssets(), file.toPath());
        return file;
    }

    /** Loads the models from a folder that already holds them. */
    SwipeRecognizer(File file) {
        try {
            base = new org.futo.ml.inference.NativeDictionary(new File(file, "english.combined").getPath());
            personal = new org.futo.ml.inference.NativeDictionary(new String[0], new float[0]);
            contractions = contractionDictionary(base);
            doubleLetters = org.futo.ml.inference.NativeDictionary.doubleLetters(base, DOUBLE_LETTER_FLOOR);
            decoder = new org.futo.ml.inference.SwipeDecoder(file.getPath());
            configure(personal);
        } catch (RuntimeException | LinkageError error) { close(); throw error; }
    }

    static org.futo.ml.inference.NativeDictionary contractionDictionary(org.futo.ml.inference.NativeDictionary base) {
        Map<String, Float> spellings = SwipeSpelling.contractions(base::frequency);
        String[] words = spellings.keySet().toArray(new String[0]);
        float[] frequencies = new float[words.length];
        for (int i = 0; i < words.length; i++) frequencies[i] = spellings.get(words[i]);
        return new org.futo.ml.inference.NativeDictionary(words, frequencies);
    }

    /** The dictionaries in the order of the recognition weights, then any extra ones. */
    private void configure(org.futo.ml.inference.NativeDictionary personal, long... extra) {
        long[] pointers = java.util.Arrays.copyOf(new long[] { base.pointer(), personal.pointer(), contractions.pointer(),
                doubleLetters.pointer() }, 4 + extra.length);
        System.arraycopy(extra, 0, pointers, 4, extra.length);
        decoder.dictionaries(Layout.LETTERS, Layout.KEY_X, Layout.KEY_Y, pointers);
    }

    public void personalWords(String[] words, float[] frequencies) {
        Set<String> learned = Set.copyOf(java.util.Arrays.asList(words));
        org.futo.ml.inference.NativeDictionary replacement = new org.futo.ml.inference.NativeDictionary(words, frequencies);
        try { configure(replacement); }
        catch (RuntimeException | LinkageError error) {
            // A failed configuration may have changed native pointers before reporting failure.
            try { configure(personal); }
            catch (RuntimeException | LinkageError restoreError) { error.addSuppressed(restoreError); close(); }
            finally { replacement.close(); }
            throw error;
        }
        org.futo.ml.inference.NativeDictionary previous = personal;
        personal = replacement;
        learnedWords = learned;
        previous.close();
    }

    public float frequency(String word) { return base.frequency(word); }

    public org.futo.ml.inference.SwipeDecoder.Result[] recognise(Swipe gesture, String[] context, boolean personalized) {
        return recognise(gesture, context, personalized, word -> true);
    }

    org.futo.ml.inference.SwipeDecoder.Result[] recognise(Swipe gesture, String[] context, boolean personalized,
            Predicate<String> allowed) {
        org.futo.ml.inference.SwipeDecoder.Result[] results = decoder.recognise(
                gesture.x, gesture.y, gesture.time, scoringContext(context), new float[]{1, personalized ? 1 : 0, 1, 1},
                personalized ? SAVED_WORD_CANDIDATES : CANDIDATES);
        if (personalized) results = savedWords(results, learnedWords, decoder.contextWeight());
        rawResults = results.clone();
        results = java.util.Arrays.stream(contractionSpellings(results, decoder.contextWeight())).filter(result -> allowed.test(result.word))
                .toArray(org.futo.ml.inference.SwipeDecoder.Result[]::new);
        return rank(gesture, results, decoder.contextWeight(), decoder.shapeNormalizer(), context.length,
                personalized ? learnedWords : Set.of());
    }

    /**
     * Keeps the engine's usual words, and any saved word from the deeper search. Saved words are mostly names
     * that the context model has never seen, so its score for them is a guess from their letters. They take the
     * best context score instead, and the swipe's shape decides.
     */
    static org.futo.ml.inference.SwipeDecoder.Result[] savedWords(org.futo.ml.inference.SwipeDecoder.Result[] results,
            Set<String> learnedWords, float contextWeight) {
        int count = Math.min(CANDIDATES, results.length);
        java.util.List<org.futo.ml.inference.SwipeDecoder.Result> kept = new java.util.ArrayList<>(Arrays.asList(results).subList(0, count));
        // The engine always keeps its best shape match, in the last place when it would otherwise miss the list.
        org.futo.ml.inference.SwipeDecoder.Result shapeBest = null;
        for (org.futo.ml.inference.SwipeDecoder.Result result : results)
            if (shapeBest == null || result.ctcScore > shapeBest.ctcScore) shapeBest = result;
        if (shapeBest != null && !kept.contains(shapeBest)) kept.set(count - 1, shapeBest);
        for (org.futo.ml.inference.SwipeDecoder.Result result : results)
            if (learnedWords.contains(result.word) && !kept.contains(result)) kept.add(result);
        float bestContext = Float.NEGATIVE_INFINITY;
        for (org.futo.ml.inference.SwipeDecoder.Result result : kept) bestContext = Math.max(bestContext, result.lmScore);
        org.futo.ml.inference.SwipeDecoder.Result[] scored = new org.futo.ml.inference.SwipeDecoder.Result[kept.size()];
        for (int i = 0; i < scored.length; i++) {
            org.futo.ml.inference.SwipeDecoder.Result result = kept.get(i);
            scored[i] = learnedWords.contains(result.word) ? new org.futo.ml.inference.SwipeDecoder.Result(result.word,
                    result.score + contextWeight * (bestContext - result.lmScore), result.ctcScore, bestContext) : result;
        }
        Arrays.sort(scored, (a, b) -> Float.compare(b.score, a.score));
        return scored;
    }

    /**
     * Keeps one result per spelling, as "dont" and "don't" display identically. Expects the best score first.
     * The context model rates some contractions far too low, such as "I'm", so one with no other reading
     * is never penalised for its context.
     */
    static org.futo.ml.inference.SwipeDecoder.Result[] contractionSpellings(org.futo.ml.inference.SwipeDecoder.Result[] results, float contextWeight) {
        Map<String, org.futo.ml.inference.SwipeDecoder.Result> spellings = new java.util.LinkedHashMap<>();
        for (org.futo.ml.inference.SwipeDecoder.Result result : results) {
            String spelling = SwipeSpelling.contraction(result.word);
            boolean neutral = result.lmScore < 0 && SwipeSpelling.onlyContraction(spelling);
            spellings.putIfAbsent(spelling, new org.futo.ml.inference.SwipeDecoder.Result(spelling,
                    neutral ? result.score - contextWeight * result.lmScore : result.score,
                    result.ctcScore, neutral ? 0 : result.lmScore));
        }
        return spellings.values().toArray(new org.futo.ml.inference.SwipeDecoder.Result[0]);
    }

    static String[] scoringContext(String[] context) { return context.length == 0 ? SENTENCE_START : context; }

    /** The earlier words stay the same while a word is typed, so each letter reuses the last answer. */
    public Map<String, Double> nextWords(String[] context) {
        if (nextWords != null && Arrays.equals(context, nextWordsContext)) return nextWords;
        Map<String, Double> scores = new HashMap<>();
        for (org.futo.ml.inference.SwipeDecoder.Result result : decoder.predictNext(scoringContext(context), NEXT_WORD_CANDIDATES))
            scores.putIfAbsent(TypedPredictions.key(result.word), (double) result.score);
        nextWordsContext = context.clone();
        nextWords = java.util.Collections.unmodifiableMap(scores);
        return nextWords;
    }

    /** The look-alike of this word that the next word makes clearly more likely, or null to leave it. */
    public String lookAlike(String[] context, String word, String next, boolean typed) {
        return lookAlike(decoder, context, word, next, typed);
    }

    /**
     * The next word must point to the look-alike strongly. A swiped word was already chosen from the earlier words,
     * so they need only not outweigh it. A typed word was not, so the earlier words must also agree.
     */
    private static String lookAlike(org.futo.ml.inference.SwipeDecoder decoder, String[] context, String word, String next, boolean typed) {
        String other = SwipeSpelling.lookAlike(word);
        if (other == null) return null;
        String shown = SwipeSpelling.restore(SwipeSpelling.lookAlike(other));
        String replacement = SwipeSpelling.restore(other);
        double after = chance(decoder, followedBy(context, replacement), next) - chance(decoder, followedBy(context, shown), next);
        if (after < (CASUAL_LOOK_ALIKES.contains(other) ? 0 : LOOK_ALIKE_NEXT_WORD_EVIDENCE)) return null;
        String[] before = scoringContext(context);
        double earlier = chance(decoder, before, replacement) - chance(decoder, before, shown);
        return after + earlier >= (typed ? TYPED_LOOK_ALIKE_EVIDENCE : 0) ? other : null;
    }

    private static String[] followedBy(String[] context, String word) {
        String[] words = Arrays.copyOf(context, context.length + 1);
        words[context.length] = word;
        return words;
    }

    /** The log chance of a word after this context, across the words the context model suggests. */
    private static double chance(org.futo.ml.inference.SwipeDecoder decoder, String[] context, String word) {
        org.futo.ml.inference.SwipeDecoder.Result[] results = decoder.predictNext(context, LOOK_ALIKE_CANDIDATES);
        if (results.length == 0) return 0;
        double best = Double.NEGATIVE_INFINITY;
        for (org.futo.ml.inference.SwipeDecoder.Result result : results) best = Math.max(best, result.score);
        double total = 0;
        double found = results[results.length - 1].score;
        boolean matched = false;
        for (org.futo.ml.inference.SwipeDecoder.Result result : results) {
            total += Math.exp(result.score - best);
            if (!matched && result.word.equalsIgnoreCase(word)) {
                found = result.score;
                matched = true;
            }
        }
        return found - best - Math.log(total);
    }

    public static org.futo.ml.inference.SwipeDecoder.Result[] rank(Swipe gesture,
            org.futo.ml.inference.SwipeDecoder.Result[] results, float contextWeight, float shapeNormalizer,
            int contextWords) {
        return rank(gesture, results, contextWeight, shapeNormalizer, contextWords, Set.of());
    }

    static org.futo.ml.inference.SwipeDecoder.Result[] rank(Swipe gesture,
            org.futo.ml.inference.SwipeDecoder.Result[] results, float contextWeight, float shapeNormalizer,
            int contextWords, Set<String> learnedWords) {
        results = java.util.Arrays.stream(results).filter(result -> SuggestionPolicy.allowed(result.word))
                .toArray(org.futo.ml.inference.SwipeDecoder.Result[]::new);
        org.futo.ml.inference.SwipeDecoder.Result shapeBest = null;
        for (org.futo.ml.inference.SwipeDecoder.Result result : results) {
            if (shapeBest == null || result.ctcScore > shapeBest.ctcScore) shapeBest = result;
        }
        boolean clearEndpoints = shapeBest != null
                && endpointDistance(gesture, shapeBest.word, false) <= .25f
                && endpointDistance(gesture, shapeBest.word, true) <= .25f;
        float contextLimit = MAX_CONTEXT_SCORE;
        // A sentence start is a weak hint, so it gets the same small limit as a clean swipe.
        if (clearEndpoints || contextWords == 0) contextLimit = .5f;
        else if (contextWords >= 2) contextLimit = MAX_SENTENCE_CONTEXT_SCORE;
        // Measure each word against the likeliest word, so that two likely words do not tie at the limit.
        // The gap may reach twice the limit, the same range as limiting scores in both directions.
        float bestContext = Float.NEGATIVE_INFINITY;
        for (org.futo.ml.inference.SwipeDecoder.Result result : results) {
            bestContext = Math.max(bestContext, contextWeight * result.lmScore);
        }
        Map<String, EndpointScores> groups = new HashMap<>();
        float[] adjustedScores = new float[results.length];
        for (int i = 0; i < results.length; i++) {
            org.futo.ml.inference.SwipeDecoder.Result result = results[i];
            float contextScore = contextWeight * result.lmScore;
            float boundedContext = Math.max(-2 * contextLimit, contextScore - bestContext);
            float penalty = endpointPenalty(gesture, result.word, false) + endpointPenalty(gesture, result.word, true);
            float adjusted = result.score - penalty - contextScore + boundedContext;
            adjustedScores[i] = adjusted;
            EndpointScores group = groups.computeIfAbsent(endpointGroup(result.word), key -> new EndpointScores());
            double pathScore = result.ctcScore / Math.pow(Math.max(1, result.word.replace("'", "").length()), shapeNormalizer);
            group.pathBest = Math.max(group.pathBest, pathScore);
            if (learnedWords.contains(result.word)) group.learnedPath = Math.max(group.learnedPath, pathScore);
            if (!learnedWords.contains(result.word) && result.score > group.nativeBest) { group.nativeBest = result.score; group.nativePath = pathScore; }
            group.adjustedBest = Math.max(group.adjustedBest, adjusted);
        }
        // Keep context bounded when a learned word is the best shape match in its group.
        for (int i = 0; i < results.length; i++) {
            org.futo.ml.inference.SwipeDecoder.Result result = results[i];
            EndpointScores group = groups.get(endpointGroup(result.word));
            float score = group.learnedPath < group.pathBest && group.nativePath >= group.pathBest - MAX_PATH_SCORE_GAP
                    ? group.adjustedBest + (result.score - group.nativeBest) : adjustedScores[i];
            results[i] = new org.futo.ml.inference.SwipeDecoder.Result(
                    result.word, score, result.ctcScore, result.lmScore);
        }
        java.util.Arrays.sort(results, (a, b) -> Float.compare(b.score, a.score));
        return java.util.Arrays.copyOf(results, Math.min(3, results.length));
    }

    private static String endpointGroup(String word) {
        return word.isEmpty() ? "" : word.charAt(0) + ":" + word.charAt(word.length() - 1);
    }

    private static final class EndpointScores {
        float nativeBest = Float.NEGATIVE_INFINITY;
        float adjustedBest = Float.NEGATIVE_INFINITY;
        double pathBest = Double.NEGATIVE_INFINITY;
        double learnedPath = Double.NEGATIVE_INFINITY;
        double nativePath;
    }

    private static float endpointPenalty(Swipe gesture, String word, boolean end) {
        float distance = endpointDistance(gesture, word, end);
        return Float.isFinite(distance) ? 3f * Math.min(4, distance) : 0;
    }

    private static float endpointDistance(Swipe gesture, String word, boolean end) {
        if (word.isEmpty() || gesture.x.length == 0) return Float.POSITIVE_INFINITY;
        int key = Layout.LETTERS.indexOf(word.charAt(end ? word.length() - 1 : 0));
        if (key < 0) return Float.POSITIVE_INFINITY;
        int point = end ? gesture.x.length - 1 : 0;
        float dx = (gesture.x[point] - Layout.KEY_X[key]) / 0.1f;
        float dy = (gesture.y[point] - Layout.KEY_Y[key]) / (1f / 3f);
        return dx * dx + dy * dy;
    }

    public boolean fits(Swipe gesture, String[] context, String word,
                        org.futo.ml.inference.SwipeDecoder.Result baseline) {
        if (baseline == null) return false;
        org.futo.ml.inference.NativeDictionary probe = new org.futo.ml.inference.NativeDictionary(new String[]{word}, new float[]{64});
        try {
            configure(personal, probe.pointer());
            org.futo.ml.inference.SwipeDecoder.Result[] candidates = decoder.recognise(
                    gesture.x, gesture.y, gesture.time, context, new float[]{0, 0, 0, 0, 1});
            if (candidates.length == 0) return false;
            return plausible(candidates[0], baseline);
        } finally {
            try { configure(personal); }
            catch (RuntimeException | LinkageError error) { close(); throw error; }
            finally { probe.close(); }
        }
    }

    public boolean plausible(org.futo.ml.inference.SwipeDecoder.Result candidate,
                             org.futo.ml.inference.SwipeDecoder.Result baseline) {
        if (candidate == null || baseline == null) return false;
        double normalizer = decoder.shapeNormalizer();
        double score = candidate.ctcScore / Math.pow(candidate.word.replace("'", "").length(), normalizer);
        double reference = baseline.ctcScore / Math.pow(baseline.word.replace("'", "").length(), normalizer);
        return Double.isFinite(score) && score >= reference - MAX_PATH_SCORE_GAP;
    }

    public String[] recognise(Swipe gesture, String[] context) {
        return java.util.Arrays.stream(recognise(gesture, context, false)).map(result -> result.word).toArray(String[]::new);
    }

    public boolean isOpen() { return decoder != null && base != null && personal != null && contractions != null
            && doubleLetters != null; }

    @Override public void close() {
        try { if (decoder != null) decoder.close(); }
        finally {
            decoder = null;
            try { if (personal != null) personal.close(); }
            finally {
                personal = null;
                try { if (contractions != null) contractions.close(); }
                finally {
                    contractions = null;
                    try { if (doubleLetters != null) doubleLetters.close(); }
                    finally {
                        doubleLetters = null;
                        try { if (base != null) base.close(); }
                        finally { base = null; }
                    }
                }
            }
        }
    }
}
