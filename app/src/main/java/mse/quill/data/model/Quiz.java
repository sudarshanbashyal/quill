package mse.quill.data.model;

/**
 * A note that has been turned into a quiz, plus the summary its list row shows.
 *
 * <p>There is no question set here on purpose: the questions are regenerated from the note's Q&amp;A
 * blocks every time an attempt starts, so a quiz is really just the record that the note is one —
 * and the place its attempt history hangs off.
 */
public class Quiz {

    public String id;
    public String noteId;
    public String noteTitle;
    public long createdAt;

    /** Completed attempts. In-progress and abandoned ones are history, not results. */
    public int attempts;
    /** Best completed score as a percentage, or null if it has never been finished. */
    public Integer bestPercent;
    /** When it was last *started*, or null if never — abandoned runs count as having taken it. */
    public Long lastAttemptAt;
}
