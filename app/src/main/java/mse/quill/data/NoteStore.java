package mse.quill.data;

import java.util.List;
import java.util.Set;

import mse.quill.data.model.Note;
import mse.quill.data.model.NoteSegment;

/**
 * What the app outside {@code data/} is allowed to do with notes.
 *
 * <p><b>Not every public method on {@link NoteRepository} — only the ones with callers outside
 * this package.</b> The blocking {@code Sync} variants are deliberately absent: they exist for
 * code already on a background thread (the bundle writer, the widgets' {@code RemoteViewsService})
 * which knows it is talking to a database, and putting them here would invite a screen to call one
 * on the main thread. Those callers keep the concrete type, and that is the honest signal that
 * they are doing something a screen should not.
 *
 * <p>This interface <em>is</em> the information-hiding boundary — everything else about how a note
 * is stored (that a note is one Markdown document, that its body may be encrypted, that there is
 * an FTS index to keep current) stays behind it. It is also the seam a fake can be substituted at,
 * which is the point: repository tests had to live in {@code androidTest} and need a device
 * because there was previously no way to stand in for the data layer at all.
 *
 * <p>Obtain one from {@link Repositories#notes}, not with {@code new}.
 */
public interface NoteStore {

    /** At most three notes may be pinned; {@link OnPinResult#onLimitReached} says when that bites. */
    int MAX_PINNED_NOTES = NoteRepository.MAX_PINNED_NOTES;

    interface OnNoteLoaded { void onLoaded(Note note, List<NoteSegment> segments); }

    interface OnNotesLoaded { void onLoaded(List<Note> notes); }

    interface OnPinResult { void onPinned(); void onLimitReached(); }

    /** Outcome of a save into a collection that may be encrypted. */
    interface OnNoteSaved {
        void onSaved();

        /**
         * The note's collection is locked and its key would not encrypt — the authentication
         * window closed while the note was open. <b>Nothing was written.</b> The editor still
         * holds the text, so the caller's job is to get the collection unlocked and save again,
         * not to warn about lost work.
         */
        default void onNeedsUnlock() {}
    }

    /** A note, paired with how many of its Q&amp;A blocks could become cards right now. */
    final class QaCandidate {
        public final Note note;
        public final int usableQa;

        QaCandidate(Note note, int usableQa) {
            this.note = note;
            this.usableQa = usableQa;
        }
    }

    interface OnQaCandidatesLoaded { void onLoaded(List<QaCandidate> candidates); }

    interface OnSearchMatches {
        /** {@code null} means "no answer" — an unusable query, or a build without FTS5. Callers
         *  fall back to matching what they already hold in memory rather than showing nothing. */
        void onMatched(Set<String> noteIds);
    }

    /** Mints an id for a note that is about to be created. Static rather than an instance
     *  method because a caller needs the id <em>before</em> there is a note to ask. */
    static String newNoteId() {
        return NoteRepository.newNoteId();
    }

    void createNote(String noteId, String title, String collectionId, Runnable onCreated);

    void loadNote(String noteId, OnNoteLoaded cb);

    void loadNotes(String filter, OnNotesLoaded cb);

    void loadPinnedNotes(OnNotesLoaded cb);

    /** Every note that could produce at least one card — what the Flashcards and Quizzes tabs
     *  offer when you ask them to make something. */
    void loadQaCandidates(OnQaCandidatesLoaded cb);

    void saveNote(String noteId, String title, List<NoteSegment> segments, Runnable onSaved);

    void saveNote(String noteId, String title, List<NoteSegment> segments, OnNoteSaved cb);

    void assignCollection(String noteId, String collectionId, Runnable onDone);

    void pinNote(String noteId, OnPinResult cb);

    void unpinNote(String noteId, Runnable onDone);

    void deleteNote(String noteId, Runnable onDeleted);

    /** The ids of every indexed note matching {@code rawQuery}, title or body. */
    void searchNoteIds(String rawQuery, OnSearchMatches cb);
}
