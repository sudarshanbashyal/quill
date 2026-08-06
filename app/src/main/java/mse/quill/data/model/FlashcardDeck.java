package mse.quill.data.model;

/**
 * A note's flashcards, summarised for the decks list.
 *
 * <p>Counted in SQL rather than by loading every card, because the list only ever shows totals —
 * a note with 200 cards costs the same as one with three.
 */
public class FlashcardDeck {

    public String noteId;
    public String noteTitle;

    /** Every card generated from the note. */
    public int total;
    /** Cards due now — what "reviews left" means on this screen. */
    public int due;
    /** Cards never answered yet; a subset of {@link #due}, since a new card is due immediately. */
    public int unseen;

    /** When the earliest card comes back around, in epoch millis. */
    public long nextReview;
    /** Null until some card in the deck has been reviewed. */
    public Long lastReviewedAt;
}
