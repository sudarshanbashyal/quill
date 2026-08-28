package mse.quill.data.model;
import mse.quill.study.scheduling.FlashcardScheduler;

/**
 * One reviewable card, generated from a Q&amp;A block in a note.
 *
 * <p>{@link #front} and {@link #back} hold Markdown, not plain text, so a bolded term or a bulleted
 * answer looks on the card the way it looks in the note — the same round trip the note document
 * itself uses.
 *
 * <p>The scheduling fields are SM-2's state, advanced by {@code FlashcardScheduler} after each
 * answer and never touched by a re-sync of the source note.
 */
public class Flashcard {

    public String id;
    public String noteId;
    /** The id of the Q&amp;A block this came from, as stored in the note's Markdown fence. */
    public String sourceSegmentId;

    public String front;
    public String back;

    /** Days between the last review and the next one. */
    public int interval;
    /** Consecutive correct answers; reset to 0 by a wrong one. */
    public int repetitions;
    /** SM-2 easiness factor — how quickly this card's interval grows. */
    public double easiness;
    /** When the card next becomes due, in epoch millis. */
    public long nextReview;
    /** Null until the card has been answered at least once. */
    public Long lastReviewedAt;

    public boolean isDue(long now) {
        return nextReview <= now;
    }

    public boolean isNew() {
        return lastReviewedAt == null;
    }
}
