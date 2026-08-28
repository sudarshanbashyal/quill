package mse.quill.study.quiz;

/**
 * The knobs a quiz is shaped by, in one place because all three are guesses that will want tuning
 * once anyone has actually sat a quiz.
 *
 * <p>They aren't independent: {@link #MIN_QA_BLOCKS} is the floor that makes {@link
 * #OPTIONS_PER_QUESTION} possible — a question needs its own answer plus three wrong ones drawn
 * from <em>other</em> blocks in the same note, so four options need four blocks, and the fifth is
 * what stops every question from being built out of the same three distractors.
 */
public final class QuizRules {

    private QuizRules() {}

    /** How many complete Q&amp;A blocks a note needs before it can become a quiz. */
    public static final int MIN_QA_BLOCKS = 5;

    /** Options shown per question, the correct one included. */
    public static final int OPTIONS_PER_QUESTION = 4;

    /**
     * The clock's allowance <em>per question</em> — but the clock itself runs for the whole quiz.
     *
     * <p>A per-question timer forces the same pace onto a one-line recall and a question worth
     * thinking about; one budget for the run lets the user spend it where it's needed, and going
     * back to an earlier answer stays possible because nothing is sealed when a question is left.
     * See {@link #totalTimeMs}.
     */
    public static final long QUESTION_TIME_MS = 15_000L;

    /** When the run's remaining time drops below this, the clock says so and turns red. */
    public static final long WARNING_TIME_MS = 10_000L;

    /** The whole run's budget: every question's allowance, spendable in any order. */
    public static long totalTimeMs(int questionCount) {
        return QUESTION_TIME_MS * Math.max(0, questionCount);
    }

    /**
     * How long past its theoretical end an unfinished attempt is still believed.
     *
     * <p>Leaving a quiz marks the attempt abandoned on the way out, so this only catches attempts
     * whose process died before that could run — generous, because the alternative is retiring an
     * attempt someone is still sitting.
     */
    public static final long ABANDON_GRACE_MS = 10 * 60 * 1000L;
}
