package mse.quill.study.quiz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Builds a quiz out of one note's Q&amp;A blocks.
 *
 * <p>Every wrong option is a <em>real answer to another question in the same note</em>. That's the
 * whole trick, and it's why quizzes are per-note: a note is already a topically coherent set, so
 * its other answers are wrong but plausible, which is exactly what a distractor has to be. Inventing
 * them (or asking a model for them) would need either judgement the app doesn't have or a network
 * call the app doesn't make.
 *
 * <p>Plain Java, no Android, deliberately taking its {@link Random} — the interesting behaviour here
 * is what gets sampled, and that's only testable if the shuffling can be pinned down.
 */
public final class QuizGenerator {

    private QuizGenerator() {}

    /** A Q&amp;A block flattened to the two strings a question is built from. */
    public static final class QaPair {
        public final String id;
        public final String question;
        public final String answer;

        public QaPair(String id, String question, String answer) {
            this.id = id;
            this.question = question;
            this.answer = answer;
        }
    }

    /**
     * Generates one question per usable block, in a shuffled order.
     *
     * <p>Returns empty rather than a short quiz when there aren't enough blocks: below {@link
     * QuizRules#MIN_QA_BLOCKS} the same few answers would have to be reused as the distractors for
     * every question, and a quiz you can pass by elimination isn't one. Callers check the count
     * first and explain the shortfall — reaching here with too few is a fallback, not the message.
     */
    public static List<QuizQuestion> generate(List<QaPair> pairs, Random random) {
        List<QuizQuestion> questions = new ArrayList<>();
        if (pairs == null || pairs.size() < QuizRules.MIN_QA_BLOCKS) return questions;

        List<QaPair> order = new ArrayList<>(pairs);
        Collections.shuffle(order, random);

        for (QaPair pair : order) {
            List<String> options = new ArrayList<>();
            options.add(pair.answer);
            options.addAll(distractorsFor(pair, pairs, random));

            // Below three distractors the question is still askable — it just offers fewer ways to
            // be wrong. That only happens when other blocks repeat this block's answer, which is a
            // note that says the same thing twice rather than a failure to generate.
            Collections.shuffle(options, random);
            questions.add(new QuizQuestion(pair.id, pair.question, options,
                    options.indexOf(pair.answer)));
        }
        return questions;
    }

    /**
     * Wrong answers for one question: other blocks' answers, deduplicated.
     *
     * <p>Matching is case- and whitespace-insensitive, because two options that differ only in
     * capitalisation read as one option repeated — and if the repeat happens to be the correct
     * answer, the question has two right answers and no way to pick between them.
     */
    private static List<String> distractorsFor(QaPair pair, List<QaPair> pool, Random random) {
        Set<String> seen = new LinkedHashSet<>();
        seen.add(normalise(pair.answer));

        List<String> candidates = new ArrayList<>();
        for (QaPair other : pool) {
            if (other == pair) continue;
            if (!seen.add(normalise(other.answer))) continue;
            candidates.add(other.answer);
        }

        Collections.shuffle(candidates, random);
        int wanted = Math.min(QuizRules.OPTIONS_PER_QUESTION - 1, candidates.size());
        return new ArrayList<>(candidates.subList(0, wanted));
    }

    private static String normalise(String text) {
        return text == null ? "" : text.trim().toLowerCase();
    }
}
