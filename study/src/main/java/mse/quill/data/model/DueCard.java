package mse.quill.data.model;

/**
 * One card as the watch sees it — the projection of a {@link Flashcard}, not a copy of one.
 *
 * <p>Four fields, and the three that are missing are the point. There is no easiness, no interval
 * and no repetition count, because SM-2 state is never computed on the watch: an answer travels
 * back as an event and the <em>phone's</em> scheduler advances the card. A watch holding easiness
 * would be a watch that could disagree with the phone about it.
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
}
