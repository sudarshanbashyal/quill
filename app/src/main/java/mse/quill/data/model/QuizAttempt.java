package mse.quill.data.model;

/**
 * One sitting of a quiz, as stored.
 *
 * <p>A row is written when the attempt <em>starts</em>, not when it ends — otherwise walking out of
 * a quiz halfway would leave no trace of it, and "I gave up on this one" is worth as much in a
 * history as a score is.
 */
public class QuizAttempt {

    /** Started and not yet resolved: the user is sitting it now, or the app died while they were. */
    public static final String STATUS_IN_PROGRESS = "in_progress";
    /** Every question answered. */
    public static final String STATUS_COMPLETED = "completed";
    /** Left before the last question. The score is what had been answered correctly up to then. */
    public static final String STATUS_ABANDONED = "abandoned";

    public String id;
    public String quizId;
    public int score;
    /** Questions actually reached. Equal to {@link #total} for a completed attempt. */
    public int answered;
    public int total;
    public String status;
    public long startedAt;
    /** Null while in progress. */
    public Long finishedAt;

    public boolean isCompleted() {
        return STATUS_COMPLETED.equals(status);
    }

    /** Score as a percentage, guarding the empty quiz that a division would otherwise blow up on. */
    public int percent() {
        return total <= 0 ? 0 : Math.round(score * 100f / total);
    }
}
