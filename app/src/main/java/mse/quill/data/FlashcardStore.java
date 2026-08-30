package mse.quill.data;

import java.util.List;

import mse.quill.data.model.Flashcard;
import mse.quill.data.model.FlashcardDeck;
import mse.quill.data.model.NoteSegment;
import mse.quill.data.model.QaSegment;

/**
 * What the app outside {@code data/} is allowed to do with flashcards.
 *
 * <p>Same rules as {@link NoteStore}: only the methods with callers outside this package, and no
 * blocking {@code Sync} variants — {@code countDueSync}, {@code loadDecksForWidgetSync} and
 * {@code loadDueCardsForWidgetSync} belong to a worker and two widgets that are already on a
 * background thread, and they keep the concrete {@link FlashcardRepository}.
 *
 * <p>Everything behind this — SM-2 scheduling, orphan marking when a note's Q&amp;A block goes
 * away, due counts — stays behind it.
 *
 * <p>Obtain one from {@link Repositories#flashcards}, not with {@code new}.
 */
public interface FlashcardStore {

    interface OnDeckLoaded { void onLoaded(List<Flashcard> deck); }

    interface OnDecksLoaded { void onLoaded(List<FlashcardDeck> decks); }

    interface OnCounted { void onCounted(int count); }

    /**
     * The Q&amp;A blocks a deck can actually be built from: both halves have to say something. A
     * question with no answer isn't a card — there'd be nothing to turn over — and it's a normal
     * intermediate state while writing a note, so it's skipped rather than flagged.
     *
     * <p>A pure function of the segments, so it is asked without a store — the editor uses it to
     * decide whether offering "make a deck" would mean anything.
     */
    static List<QaSegment> reviewableQa(List<NoteSegment> segments) {
        return FlashcardRepository.reviewableQa(segments);
    }

    /** Brings a note's deck in line with its Q&amp;A blocks — new cards added, vanished ones
     *  marked orphaned rather than deleted, so editing the block back revives them. */
    void syncFromNote(String noteId, List<NoteSegment> segments, OnDeckLoaded cb);

    void loadDecks(OnDecksLoaded cb);

    void loadDueAcrossNotes(long now, OnDeckLoaded cb);

    void countForNote(String noteId, OnCounted cb);

    void recordReview(Flashcard card, boolean correct, Runnable onDone);

    void recordReview(Flashcard card, boolean correct, long answeredAt, Runnable onDone);

    void deleteForNote(String noteId, Runnable onDeleted);
}
