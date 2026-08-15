package mse.quill.data.model;

/**
 * One card as the watch sees it — the projection of a {@link Flashcard}, not a copy of one.
 *
 * <p>What is missing is the point. There is no easiness, no interval and no repetition count,
 * because SM-2 state is never computed on the watch: an answer travels back as an event and the
 * <em>phone's</em> scheduler advances the card. A watch holding easiness would be a watch that
 * could disagree with the phone about it.
 *
 * <p>{@link #front} and {@link #back} are <b>plain text</b>, unlike {@code Flashcard}'s Markdown.
 * The conversion happens on the phone, where {@code NoteDocument.toPlainText} lives — a watch
 * screen has no bullets and no bold to render them with, and shipping the markers would mean
 * shipping a Markdown renderer to draw asterisks nobody asked for.
 */
public class DueCard {

    public String id;
    public String front;
    public String back;

    /** When the card became (or becomes) due, in epoch millis — the phone's clock. */
    public long dueAt;

    /**
     * The note this card came from — the watch's grouping key, since a deck <em>is</em> a note.
     *
     * <p>Carried alongside {@link #noteTitle} rather than derived from it, because two notes may
     * share a title (and an untitled one resolves to a dated name that could collide outright).
     * Grouping on the id and displaying the title is the pair that cannot merge two decks by
     * accident.
     */
    public String noteId;

    /**
     * The deck's name, <b>already resolved</b> — an untitled note arrives as its dated fallback,
     * not as an empty string.
     *
     * <p>Resolved on the phone for the same reason the Markdown is flattened there: the fallback
     * is a localised, date-formatted string, and building it needs a {@code Context} and the
     * app's string resources. The watch would have to carry both to produce a name the phone
     * already knows.
     */
    public String noteTitle;
}
