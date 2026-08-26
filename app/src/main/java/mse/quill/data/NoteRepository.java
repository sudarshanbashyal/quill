package mse.quill.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import java.io.File;
import java.security.GeneralSecurityException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import mse.quill.data.DataChangeNotifier.Change;
import mse.quill.data.model.Note;
import mse.quill.security.CollectionLock;
import mse.quill.security.MediaFiles;
import mse.quill.data.model.Tag;
import mse.quill.data.serialization.NoteDocument;
import mse.quill.data.model.AudioSegment;
import mse.quill.data.model.ImageSegment;
import mse.quill.data.model.NoteSegment;

public class NoteRepository {

    public static final int MAX_PINNED_NOTES = 3;

    public interface OnNoteLoaded { void onLoaded(Note note, List<NoteSegment> segments); }
    public interface OnNotesLoaded { void onLoaded(List<Note> notes); }
    public interface OnPinResult { void onPinned(); void onLimitReached(); }

    /** Outcome of a save into a collection that may be encrypted. */
    public interface OnNoteSaved {
        void onSaved();

        /**
         * The note's collection is locked and its key would not encrypt — the authentication
         * window closed while the note was open. <b>Nothing was written.</b> The editor still
         * holds the text, so the caller's job is to get the collection unlocked and save again,
         * not to warn about lost work.
         */
        default void onNeedsUnlock() {}
    }

    private final AppDatabase appDatabase;
    private final AppExecutors executors;
    /** Held only so a save can re-publish the watch's note list; see {@link #saveNote}. */
    private final Context appContext;

    public NoteRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.appDatabase = AppDatabase.getInstance(appContext);
        this.executors = AppExecutors.getInstance();
    }

    /**
     * Inserts an empty note row under an id the caller has already minted.
     *
     * <p>The id is a parameter rather than something generated in here so the caller holds it the
     * moment it asks for the note, instead of one disk round-trip later. Everything it then sends
     * to {@link #saveNote} queues behind this insert on the shared single disk thread, so the
     * writes land in order without the caller having to track whether creation is still in flight.
     */
    public void createNote(String noteId, String title, String collectionId, Runnable onCreated) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            long now = System.currentTimeMillis();

            ContentValues cv = new ContentValues();
            cv.put("id", noteId);
            if (collectionId != null) cv.put("collection_id", collectionId);
            cv.put("title", title);
            cv.put("created_at", now);
            cv.put("updated_at", now);
            db.insert("notes", null, cv);
            // The Collections widget counts a collection's notes, so a new one changes a row that
            // is already on the home screen. Widgets have no periodic refresh (updatePeriodMillis
            // is 0), so a change that isn't pushed is a change that never arrives.
            DataChangeNotifier.getInstance().notifyChanged(Change.NOTES);

            if (onCreated != null) executors.mainThread(onCreated);
        });
    }

    /** Mints an id for a note that is about to be created. */
    public static String newNoteId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Whether a note still exists and is not in the trash. <b>Blocking — call from a background
     * thread.</b>
     *
     * <p>For the watch, which picks from a list that may be minutes old. Deliberately does not
     * check the collection's lock state: a locked note fails later at the save, with the proper
     * {@code onNeedsUnlock}, and answering "no such note" here would send the capture to the inbox
     * instead — silently, and to a place the user did not choose.
     */
    public boolean noteExistsSync(String noteId) {
        SQLiteDatabase db = appDatabase.getWritableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM notes WHERE id = ? AND deleted_at IS NULL",
                new String[]{noteId})) {
            return c.moveToFirst();
        }
    }

    /**
     * The id of the note captures land in, creating it the first time. <b>Blocking — call from a
     * background thread.</b>
     *
     * <p>Matched by title among notes in <em>no</em> collection, which is also where it is created.
     * Deliberately never inside one: a collection can be locked, and a capture arriving from the
     * watch while its destination is encrypted would either fail or sit in a note the user cannot
     * read. The inbox is the one place a thought can always land.
     *
     * <p>Title-matching is a weaker key than an id, and the alternative — a preference holding the
     * inbox's id — was not obviously better: it goes stale if the note is deleted, and then the
     * next capture writes to nothing at all. Matching by title recreates the note instead, which
     * is the failure everyone would rather have.
     */
    public String inboxNoteIdSync(String inboxTitle) {
        SQLiteDatabase db = appDatabase.getWritableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id FROM notes WHERE collection_id IS NULL AND title = ? "
                        + "AND deleted_at IS NULL ORDER BY created_at ASC LIMIT 1",
                new String[]{inboxTitle})) {
            if (c.moveToFirst()) return c.getString(0);
        }

        String noteId = newNoteId();
        long now = System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put("id", noteId);
        cv.put("title", inboxTitle);
        cv.put("created_at", now);
        cv.put("updated_at", now);
        db.insert("notes", null, cv);
        return noteId;
    }

    public void loadNote(String noteId, OnNoteLoaded cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            Note note = getNoteSync(db, noteId);
            List<NoteSegment> segments = note == null ? new ArrayList<>() : getSegmentsSync(db, noteId);
            if (cb != null) executors.mainThread(() -> cb.onLoaded(note, segments));
        });
    }

    public void saveNote(String noteId, String title, List<NoteSegment> segments, Runnable onSaved) {
        saveNote(noteId, title, segments, new OnNoteSaved() {
            @Override public void onSaved() {
                if (onSaved != null) onSaved.run();
            }
        });
    }

    public void saveNote(String noteId, String title, List<NoteSegment> segments, OnNoteSaved cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            Set<String> orphanedMediaPaths;
            // Whether this save changed which of the note's cards are reviewable.
            boolean flashcardsChanged;

            String markdown = NoteDocument.toMarkdown(segments);

            String collectionId = NoteCrypto.collectionIdOfNote(db, noteId);
            boolean encrypted = NoteCrypto.isLocked(db, collectionId);

            // Nothing to write if nothing changed. The editor auto-saves on pause whether or not
            // anything was typed, and this used to bump updated_at regardless — so merely opening
            // a note and backing out of it reported "Updated now" on Home and jumped it to the top
            // of the list. Checked here rather than in the editor because the markdown is already
            // built on this thread, and because it fixes every caller at once.
            if (nothingToWrite(db, noteId, collectionId, encrypted, title, markdown)) {
                if (cb != null) executors.mainThread(cb::onSaved);
                return;
            }

            // Encrypted before the transaction opens, not inside it: a Keystore operation can
            // block on the TEE, and an expired authentication window throws rather than returning
            // — neither belongs inside a held write lock, and the throw must abandon the save
            // without a half-written row behind it.
            String storedTitle = title;
            byte[] storedBody = markdown.getBytes(StandardCharsets.UTF_8);
            if (encrypted) {
                try {
                    storedTitle = NoteCrypto.encryptTitle(collectionId, title);
                    storedBody = NoteCrypto.encryptBody(collectionId, markdown);
                } catch (GeneralSecurityException e) {
                    // Nothing is written, so nothing is lost that the editor isn't still holding.
                    // Telling the caller is what lets it offer to unlock and try again, instead of
                    // reporting a save that didn't happen.
                    Log.w("NoteRepository", "could not encrypt note; save abandoned", e);
                    CollectionLock.relock(collectionId);
                    if (cb != null) executors.mainThread(cb::onNeedsUnlock);
                    return;
                }
            }

            db.beginTransaction();
            try {
                ContentValues cv = new ContentValues();
                cv.put("title", storedTitle);
                cv.put("content_blob", storedBody);
                cv.put("updated_at", System.currentTimeMillis());
                db.update("notes", cv, "id = ?", new String[]{noteId});

                orphanedMediaPaths = replaceMediaAssetsSync(db, noteId, segments);
                // A photo or recording added while the collection is already shut arrives in the
                // clear — the picker and the recorder write ordinary files. Encrypting here is what
                // stops it sitting that way until the collection happens to be locked again.
                if (encrypted) convertNoteMediaSync(db, noteId, collectionId, true);
                // From the markdown rather than the segments so that the rows say exactly what the
                // stored document says — the two are built from the same list here, but the
                // migration and the lock migrations have only the markdown to work from, and one
                // source keeps all three honest.
                WhiteboardLinks.replace(db, noteId, markdown);
                // A locked note is deliberately not indexed: notes_fts stores the body as plain
                // text, so indexing one would file a readable copy of everything the encryption
                // just protected in a table with no lock on it at all. The delete covers the note
                // that was indexed before its collection was locked.
                if (encrypted) {
                    deleteFromIndexSync(db, noteId);
                } else {
                    indexNoteSync(db, noteId, title, markdown);
                }

                // Emptying half of a Q&A block takes its card out of review, and the decks list
                // has to learn that from the save — waiting for the deck screen to be opened is
                // what left a row reading "1 due" that opened onto "No cards yet". This only moves
                // the stamp on cards that already exist; it never generates a deck, so writing a
                // Q&A block into a note that has none stays exactly that.
                flashcardsChanged =
                        FlashcardRepository.markOrphansOnSaveSync(db, noteId, segments);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            for (String path : orphanedMediaPaths) {
                new File(path).delete();
            }

            if (cb != null) executors.mainThread(cb::onSaved);

            // A pinned note's row shows its title and first line, and both can change on any save;
            // so can the whiteboards a note embeds, which is what decides whether a board counts as
            // belonging to a locked collection. Before the Wear publish below, which blocks on a
            // Data Layer round trip — the widgets shouldn't wait behind the watch.
            DataChangeNotifier.getInstance().notifyChanged(Change.NOTES);
            DataChangeNotifier.getInstance().notifyChanged(Change.WHITEBOARDS);
            if (flashcardsChanged) {
                DataChangeNotifier.getInstance().notifyChanged(Change.FLASHCARDS);
            }

            // After the callback, matching recordReview and syncFromNote: the editor returns at
            // the speed of the database write, not of a Data Layer round trip. A title can change
            // on any save, and the watch's pickers are the only thing that reads this — without
            // it they would show yesterday's names until the app was next launched cold.
            WearNoteListPublisher.publishSync(appContext);
            // Only when the answer actually changed: a card leaving review (or coming back to it)
            // makes the watch's copy of what's due wrong, and it has no other way to find out
            // until its next cold start.
            if (flashcardsChanged) {
                WearProjectionPublisher.publishAfterScheduleChange(appContext);
            }
        });
    }

    /**
     * True when the note on disk already says exactly this <em>and</em> is already indexed.
     *
     * <p>Media assets aren't compared: they are derived from the same segments the markdown was
     * built from, so identical markdown means an identical asset list. The index is checked because
     * {@link #createNote} doesn't write one — a note created and then saved without being typed in
     * would otherwise be skipped here and never reach {@code notes_fts}, leaving it unsearchable.
     */
    private boolean nothingToWrite(SQLiteDatabase db, String noteId, String collectionId,
                                   boolean encrypted, String title, String markdown) {
        Cursor c = db.query("notes", new String[]{"title", "content_blob"},
                "id = ?", new String[]{noteId}, null, null, null);
        try {
            if (!c.moveToFirst()) return false;   // no row yet: this save is what creates it

            String storedTitle;
            String storedMarkdown;
            if (encrypted) {
                // Compared as plaintext, because the ciphertext can't be: GCM takes a fresh IV
                // every time, so encrypting the same note twice gives two different blobs. A
                // byte comparison would call every save a change and rewrite the row — bumping
                // updated_at, and re-encrypting, merely for opening a note.
                storedTitle = NoteCrypto.decryptTitleOrNull(collectionId, c.getString(0));
                storedMarkdown = c.isNull(1)
                        ? "" : NoteCrypto.decryptBodyOrNull(collectionId, c.getBlob(1));
                // Undecryptable: say there is something to write, and let the save path produce
                // the proper onNeedsUnlock rather than silently reporting success here.
                if (storedTitle == null && title != null) return false;
                if (storedMarkdown == null) return false;
            } else {
                storedTitle = c.getString(0);
                byte[] storedBlob = c.getBlob(1);
                storedMarkdown = storedBlob == null
                        ? "" : new String(storedBlob, StandardCharsets.UTF_8);
            }

            if (!Objects.equals(storedTitle, title) || !storedMarkdown.equals(markdown)) return false;
        } finally {
            c.close();
        }
        // A locked note is never in the index and never should be, so its absence is not the
        // "unindexed, needs a rewrite" case this check exists for.
        return encrypted || isIndexed(db, noteId);
    }

    private boolean isIndexed(SQLiteDatabase db, String noteId) {
        try (Cursor c = db.query("notes_fts", new String[]{"note_id"},
                "note_id = ?", new String[]{noteId}, null, null, null, "1")) {
            return c.moveToFirst();
        } catch (SQLiteException e) {
            // No FTS table on this device — indexing is best-effort anyway (see indexNoteSync),
            // so don't let its absence force a pointless rewrite on every save.
            return true;
        }
    }

    public void deleteNote(String noteId, Runnable onDeleted) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            db.beginTransaction();
            try {
                ContentValues cv = new ContentValues();
                cv.put("deleted_at", System.currentTimeMillis());
                db.update("notes", cv, "id = ?", new String[]{noteId});
                // Soft delete, so the document and its assets stay put — but the note must stop
                // turning up in search until (and unless) it's restored.
                deleteFromIndexSync(db, noteId);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            if (onDeleted != null) executors.mainThread(onDeleted);

            // Same rule as the watch below, for the same reason: a deleted note must stop being
            // offered anywhere it was listed. On the home screen that is the pinned row it may
            // have had, its collection's note count — and its deck, since the cards stay in the
            // table when a note is trashed (restoring the note restores them) and drop out of the
            // decks and due-now queries by their note's deleted_at alone.
            DataChangeNotifier.getInstance().notifyChanged(Change.NOTES);
            DataChangeNotifier.getInstance().notifyChanged(Change.FLASHCARDS);

            // The watch's pickers are built from this list, and until now only a *save* rebuilt it.
            // A delete therefore left the note on the wrist — offered, tappable, and gone by the
            // time a memo aimed at it reached the phone, which filed it in the inbox instead. The
            // rule this restores: anything that changes what should be on the list republishes it.
            WearNoteListPublisher.publishSync(appContext);
        });
    }

    /**
     * Moves a note between collections, re-encrypting it to match where it lands.
     *
     * <p>The conversion is the whole job. {@code collection_id} decides how the row is read — a
     * plaintext note dropped into a locked collection would be read as ciphertext and fail to
     * decrypt, and a still-encrypted note moved out of one would be handed to the editor as
     * gibberish. Changing the column without changing the bytes is therefore never correct, in
     * either direction.
     *
     * <p>A move that can't be converted is abandoned rather than half-done: the note stays where
     * it is, readable, which is the only safe way to fail here.
     */
    public void assignCollection(String noteId, String collectionId, Runnable onDone) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();

            String from = NoteCrypto.collectionIdOfNote(db, noteId);
            boolean wasEncrypted = NoteCrypto.isLocked(db, from);
            boolean willBeEncrypted = NoteCrypto.isLocked(db, collectionId);

            String title;
            String markdown;
            try (Cursor c = db.rawQuery("SELECT title, content_blob FROM notes WHERE id = ?",
                    new String[]{noteId})) {
                if (!c.moveToFirst()) {
                    if (onDone != null) executors.mainThread(onDone);
                    return;
                }
                if (wasEncrypted) {
                    title = NoteCrypto.decryptTitle(from, c.getString(0));
                    markdown = c.isNull(1) ? "" : NoteCrypto.decryptBody(from, c.getBlob(1));
                } else {
                    title = c.getString(0);
                    markdown = c.isNull(1) ? "" : new String(c.getBlob(1), StandardCharsets.UTF_8);
                }
            } catch (GeneralSecurityException e) {
                Log.w("NoteRepository", "could not read note for move; leaving it put", e);
                CollectionLock.relock(from);
                if (onDone != null) executors.mainThread(onDone);
                return;
            }

            ContentValues cv = new ContentValues();
            cv.put("collection_id", collectionId);
            try {
                cv.put("title", willBeEncrypted ? NoteCrypto.encryptTitle(collectionId, title) : title);
                cv.put("content_blob", willBeEncrypted
                        ? NoteCrypto.encryptBody(collectionId, markdown)
                        : markdown.getBytes(StandardCharsets.UTF_8));
            } catch (GeneralSecurityException e) {
                Log.w("NoteRepository", "could not encrypt note for move; leaving it put", e);
                CollectionLock.relock(collectionId);
                if (onDone != null) executors.mainThread(onDone);
                return;
            }

            db.beginTransaction();
            try {
                db.update("notes", cv, "id = ?", new String[]{noteId});
                // The index follows the destination: a note moving into a locked collection has to
                // leave notes_fts, and one moving out has to rejoin it or it stays unsearchable.
                if (willBeEncrypted) {
                    deleteFromIndexSync(db, noteId);
                    // Same as locking a whole collection: the card text goes, the review schedule
                    // does not. See FlashcardRepository.suspendForLockSync.
                    FlashcardRepository.suspendForLockSync(db, noteId);
                } else {
                    indexNoteSync(db, noteId, title, markdown);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            // The note's media follows it across the boundary, or a photo would keep the format of
            // the collection it came from and be readable by the wrong key — or by none.
            convertNoteMediaSync(db, noteId, collectionId, willBeEncrypted);

            if (onDone != null) executors.mainThread(onDone);

            // A move changes both collections' counts, and — moving into a locked collection — can
            // take a pinned note off the widget entirely. It also deletes the note's flashcards on
            // the way in, which is the decks list's business.
            DataChangeNotifier.getInstance().notifyChanged(Change.NOTES);
            DataChangeNotifier.getInstance().notifyChanged(Change.FLASHCARDS);

            // A move can change whether the watch is allowed to see this note at all: into a
            // locked collection and its title has to leave the wrist, out of one and it may
            // return. Same rule as the delete above.
            WearNoteListPublisher.publishSync(appContext);
        });
    }

    /** Pins the note, unless {@link #MAX_PINNED_NOTES} notes are already pinned. */
    public void pinNote(String noteId, OnPinResult cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            Cursor c = db.rawQuery(
                    "SELECT COUNT(*) FROM notes WHERE pinned_at IS NOT NULL AND deleted_at IS NULL AND id != ?",
                    new String[]{noteId});
            int pinnedCount = 0;
            try {
                if (c.moveToFirst()) pinnedCount = c.getInt(0);
            } finally {
                c.close();
            }

            if (pinnedCount >= MAX_PINNED_NOTES) {
                if (cb != null) executors.mainThread(cb::onLimitReached);
                return;
            }

            ContentValues cv = new ContentValues();
            cv.put("pinned_at", System.currentTimeMillis());
            db.update("notes", cv, "id = ?", new String[]{noteId});
            DataChangeNotifier.getInstance().notifyChanged(Change.NOTES);
            if (cb != null) executors.mainThread(cb::onPinned);
        });
    }

    public void unpinNote(String noteId, Runnable onDone) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.putNull("pinned_at");
            db.update("notes", cv, "id = ?", new String[]{noteId});
            DataChangeNotifier.getInstance().notifyChanged(Change.NOTES);
            if (onDone != null) executors.mainThread(onDone);
        });
    }

    /** filter: null = all notes, else a collection id. */
    public void loadNotes(String filter, OnNotesLoaded cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            List<Note> notes = getAllNotesSync(db, filter, false);
            if (cb != null) executors.mainThread(() -> cb.onLoaded(notes));
        });
    }

    /** Up to {@link #MAX_PINNED_NOTES} pinned notes, most-recently-pinned first. */
    public void loadPinnedNotes(OnNotesLoaded cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            List<Note> notes = getAllNotesSync(db, null, true);
            if (cb != null) executors.mainThread(() -> cb.onLoaded(notes));
        });
    }

    /** Synchronous form of {@link #loadPinnedNotes}, for callers already off the main thread. */
    public List<Note> loadPinnedNotesSync() {
        return getAllNotesSync(appDatabase.getWritableDatabase(), null, true, false);
    }

    /**
     * The pinned notes a home-screen widget is allowed to show — <b>every locked collection is
     * excluded, open or not</b>, which is stricter than {@link #loadPinnedNotesSync}.
     *
     * <p>Same rule, and the same reasoning, as {@code FlashcardRepository.dueProjectionSync} uses
     * for the watch: in the app the question is "should this appear on a screen the user
     * authenticated to reach", and an open collection's notes should. A widget has no session and
     * no way to ask for one, so for it the question is only "is this collection encrypted at
     * rest". Asking the session instead would make the widget's contents depend on something it
     * cannot see change — locking a collection deliberately leaves it open in-session, so the
     * notes would sit on the home screen until the app was next backgrounded.
     */
    public List<Note> loadPinnedNotesForWidgetSync() {
        return getAllNotesSync(appDatabase.getWritableDatabase(), null, true, true);
    }

    /** A note, paired with how many of its Q&amp;A blocks could become cards right now. */
    public static final class QaCandidate {
        public final Note note;
        public final int usableQa;

        QaCandidate(Note note, int usableQa) {
            this.note = note;
            this.usableQa = usableQa;
        }
    }

    public interface OnQaCandidatesLoaded { void onLoaded(List<QaCandidate> candidates); }

    /**
     * Every note that could produce at least one card, most recently updated first — what the
     * Flashcards and Quizzes tabs offer when you ask them to make something.
     *
     * <p>The count comes back with the note rather than being left to the caller, because the two
     * tabs want different amounts of it: one card is enough for a deck, a quiz needs five, and
     * showing the number in the picker is also how a note that <em>nearly</em> qualifies explains
     * itself. Filtering to "more than none" happens here so neither caller has to know that a
     * half-filled block isn't a card.
     *
     * <p>Every note's body is parsed, which is the cost of the question: whether a note has usable
     * Q&amp;A blocks is a fact about its Markdown, not something the notes table records. Acceptable
     * because this runs once, off the main thread, when a picker is opened — not on any list path.
     */
    public void loadQaCandidates(OnQaCandidatesLoaded cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            List<QaCandidate> candidates = new ArrayList<>();
            // getAllNotesSync rather than a query of its own: it is what already knows to leave
            // shut collections out and to decrypt the open ones, and a picker that offered a note
            // the rest of the app is hiding would be a hole in the lock.
            for (Note note : getAllNotesSync(db, null, false)) {
                // The media map is empty on purpose. It only matters for image and audio segments,
                // and nothing here looks at those — a Q&A block is carried entirely by the
                // Markdown.
                List<NoteSegment> segments =
                        NoteDocument.fromMarkdown(getMarkdownSync(db, note.id), new HashMap<>());
                int usable = FlashcardRepository.reviewableQa(segments).size();
                if (usable > 0) candidates.add(new QaCandidate(note, usable));
            }
            if (cb != null) executors.mainThread(() -> cb.onLoaded(candidates));
        });
    }

    /** Everything a {@code .quill}/{@code .quillpack} bundle needs to carry for one note. */
    public static final class NoteBundleData {
        public final String title;
        public final List<NoteSegment> segments;
        public final List<Tag> tags;
        public final long createdAt;
        public final long updatedAt;

        NoteBundleData(String title, List<NoteSegment> segments, List<Tag> tags,
                       long createdAt, long updatedAt) {
            this.title = title;
            this.segments = segments;
            this.tags = tags;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    /**
     * Synchronous full read of one note, for a caller already on the disk thread building a
     * bundle of several notes at once (see {@code share/CollectionBundleWriter}) — the async
     * {@link #loadNote} exists for a single screen, not a loop over a collection's worth.
     *
     * @return null if the note doesn't exist, was soft-deleted, or sits in a collection that is
     *     locked and not open — {@link #getNoteSync} refuses it, and refusing here is what stops a
     *     locked note being written into a shareable bundle in the clear. Callers already ask
     *     {@code CollectionRepository.isLocked} before offering to share; this is the backstop
     *     that doesn't depend on them remembering to.
     */
    public NoteBundleData loadForBundleSync(String noteId) {
        SQLiteDatabase db = appDatabase.getWritableDatabase();
        Note note = getNoteSync(db, noteId);
        if (note == null) return null;
        List<NoteSegment> segments = getSegmentsSync(db, noteId);
        List<Tag> tags = loadTagsForNoteIdsSync(db, Collections.singletonList(noteId)).get(noteId);
        return new NoteBundleData(note.title, segments,
                tags == null ? new ArrayList<>() : tags, note.createdAt, note.updatedAt);
    }

    // ── Sync helpers (must run on the diskIO executor) ──────────────────────

    /**
     * @return null if the note doesn't exist, was soft-deleted, or belongs to a collection that is
     *     locked and not open in this session — a caller that can't read the body has no business
     *     being handed the title either.
     */
    private Note getNoteSync(SQLiteDatabase db, String noteId) {
        Cursor c = db.rawQuery(
                "SELECT id, collection_id, title, created_at, updated_at, deleted_at, pinned_at " +
                        "FROM notes WHERE id = ? AND deleted_at IS NULL",
                new String[]{noteId});
        try {
            if (!c.moveToFirst()) return null;
            Note note = readNote(c);
            if (!NoteCrypto.isLocked(db, note.collectionId)) return note;
            if (!CollectionLock.isUnlocked(note.collectionId)) return null;

            note.title = NoteCrypto.decryptTitleOrNull(note.collectionId, note.title);
            return note.title == null ? null : note;
        } finally {
            c.close();
        }
    }

    private List<Note> getAllNotesSync(SQLiteDatabase db, String filter, boolean pinnedOnly) {
        return getAllNotesSync(db, filter, pinnedOnly, false);
    }

    /**
     * @param excludeAllLocked leave out every collection encrypted at rest rather than only the
     *     ones shut right now — see {@link #loadPinnedNotesForWidgetSync}.
     */
    private List<Note> getAllNotesSync(SQLiteDatabase db, String filter, boolean pinnedOnly,
                                       boolean excludeAllLocked) {
        StringBuilder sql = new StringBuilder(
                "SELECT n.id, n.collection_id, n.title, n.created_at, n.updated_at, n.deleted_at, n.pinned_at, " +
                        "n.content_blob " +
                        "FROM notes n WHERE n.deleted_at IS NULL");

        List<String> args = new ArrayList<>();
        if (filter != null) {
            sql.append(" AND n.collection_id = ?");
            args.add(filter);
        }

        // Shut collections drop out of every list this method feeds — Home, the pinned band, the
        // collection screen — rather than each caller being trusted to remember. A pinned note in
        // a locked collection is the case that makes this the right place: it would otherwise sit
        // at the top of Home, preview and all, having never gone through a collection screen.
        Set<String> lockedIds = NoteCrypto.lockedCollectionIds(db);
        Set<String> excluded = excludeAllLocked ? lockedIds : NoteCrypto.hiddenOf(lockedIds);
        sql.append(NoteCrypto.excludeCollectionsClause(excluded));
        args.addAll(excluded);

        if (pinnedOnly) {
            sql.append(" AND n.pinned_at IS NOT NULL");
        }
        sql.append(pinnedOnly ? " ORDER BY n.pinned_at DESC" : " ORDER BY n.updated_at DESC");
        if (pinnedOnly) {
            sql.append(" LIMIT ").append(MAX_PINNED_NOTES);
        }

        Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]));
        List<Note> notes = new ArrayList<>();
        List<String> noteIds = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                Note note = readNote(c);
                boolean encrypted = lockedIds.contains(note.collectionId);

                String markdown;
                if (encrypted) {
                    // Only reachable for a collection the user has opened — the shut ones were
                    // excluded by the query above.
                    note.title = NoteCrypto.decryptTitleOrNull(note.collectionId, note.title);
                    markdown = c.isNull(7)
                            ? "" : NoteCrypto.decryptBodyOrNull(note.collectionId, c.getBlob(7));
                    // Decryption failed and relock() has already shut the collection; leaving the
                    // row out is what stops a half-readable note reaching the list.
                    if (note.title == null || markdown == null) continue;
                } else {
                    markdown = c.isNull(7)
                            ? "" : new String(c.getBlob(7), StandardCharsets.UTF_8);
                }

                note.preview = markdown.isEmpty() ? "" : NoteDocument.toPreview(markdown);
                notes.add(note);
                noteIds.add(note.id);
            }
        } finally {
            c.close();
        }

        Map<String, List<Tag>> tagsByNoteId = loadTagsForNoteIdsSync(db, noteIds);
        for (Note note : notes) {
            List<Tag> tags = tagsByNoteId.get(note.id);
            if (tags != null) note.tags = tags;
        }
        return notes;
    }

    private static Note readNote(Cursor c) {
        Note note = new Note();
        note.id = c.getString(0);
        note.collectionId = c.isNull(1) ? null : c.getString(1);
        note.title = c.getString(2);
        note.createdAt = c.getLong(3);
        note.updatedAt = c.getLong(4);
        note.deletedAt = c.isNull(5) ? null : c.getLong(5);
        note.pinnedAt = c.isNull(6) ? null : c.getLong(6);
        return note;
    }

    private Map<String, List<Tag>> loadTagsForNoteIdsSync(SQLiteDatabase db, List<String> noteIds) {
        Map<String, List<Tag>> result = new HashMap<>();
        if (noteIds.isEmpty()) return result;

        String placeholders = String.join(",", Collections.nCopies(noteIds.size(), "?"));
        Cursor c = db.rawQuery(
                "SELECT nt.note_id, t.id, t.name, t.color, t.created_at FROM note_tags nt " +
                        "JOIN tags t ON t.id = nt.tag_id " +
                        "WHERE nt.note_id IN (" + placeholders + ") " +
                        "ORDER BY t.name COLLATE NOCASE ASC",
                noteIds.toArray(new String[0]));
        try {
            while (c.moveToNext()) {
                String noteId = c.getString(0);
                Tag tag = new Tag();
                tag.id = c.getString(1);
                tag.name = c.getString(2);
                tag.color = c.getInt(3);
                tag.createdAt = c.getLong(4);
                result.computeIfAbsent(noteId, k -> new ArrayList<>()).add(tag);
            }
        } finally {
            c.close();
        }
        return result;
    }

    /** The note's Markdown document, parsed back into the segments the editor renders. */
    private List<NoteSegment> getSegmentsSync(SQLiteDatabase db, String noteId) {
        return NoteDocument.fromMarkdown(getMarkdownSync(db, noteId), getMediaAssetsSync(db, noteId));
    }

    private String getMarkdownSync(SQLiteDatabase db, String noteId) {
        Cursor c = db.rawQuery(
                "SELECT content_blob, collection_id FROM notes WHERE id = ?", new String[]{noteId});
        try {
            if (!c.moveToFirst() || c.isNull(0)) return "";
            byte[] stored = c.getBlob(0);
            String collectionId = c.isNull(1) ? null : c.getString(1);

            if (!NoteCrypto.isLocked(db, collectionId)) {
                return new String(stored, StandardCharsets.UTF_8);
            }
            // Empty rather than ciphertext-as-text on failure: callers parse this as Markdown, and
            // handing them random bytes would render as garbage in the editor instead of as the
            // "can't read this" that it is. getNoteSync has already refused the note in that case,
            // so a caller reaching here with an unreadable body has nothing to display anyway.
            String markdown = NoteCrypto.decryptBodyOrNull(collectionId, stored);
            return markdown == null ? "" : markdown;
        } finally {
            c.close();
        }
    }

    /** Every media asset belonging to the note, keyed by id — the document decides which of them
     *  actually appear and in what order. */
    private Map<String, NoteSegment> getMediaAssetsSync(SQLiteDatabase db, String noteId) {
        Map<String, NoteSegment> assets = new HashMap<>();
        Cursor c = db.rawQuery(
                "SELECT id, type, file_path, width, duration_ms FROM note_segments WHERE note_id = ?",
                new String[]{noteId});
        try {
            while (c.moveToNext()) {
                int type = c.getInt(1);
                NoteSegment segment;
                if (type == NoteSegment.TYPE_IMAGE) {
                    ImageSegment image = new ImageSegment(c.getString(2));
                    if (!c.isNull(3)) image.displayWidth = c.getInt(3);
                    segment = image;
                } else if (type == NoteSegment.TYPE_AUDIO) {
                    segment = new AudioSegment(c.getString(2), c.isNull(4) ? 0 : c.getInt(4));
                } else {
                    continue; // text isn't an asset — it's the document
                }
                segment.id = c.getString(0);
                assets.put(segment.id, segment);
            }
        } finally {
            c.close();
        }
        return assets;
    }

    /** Rewrites the note's asset rows inside the caller's transaction to match what the document
     *  now references. Returns the files that were referenced before but aren't any more, so the
     *  caller can delete them once the transaction has committed. */
    /**
     * Brings a note's image and recording files into line with whether its collection is locked.
     *
     * <p>Called from every point the answer can change: locking a collection, unlocking it, moving
     * a note across the boundary, and saving a note that added media while the collection was
     * already shut. Both directions are idempotent — {@link MediaFiles} looks at the file rather
     * than at what it was told — so running it twice, or over a note whose media is already in the
     * right state, costs a header check per file and changes nothing.
     *
     * <p>Failures are logged and skipped rather than thrown. One unreadable file must not abandon
     * the lock halfway: the note text is the greater part of what is being protected, and a
     * half-converted collection is the state hardest to reason about afterwards.
     */
    static void convertNoteMediaSync(SQLiteDatabase db, String noteId, String collectionId,
                                     boolean encrypt) {
        List<String> paths = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT file_path FROM note_segments WHERE note_id = ? AND file_path IS NOT NULL",
                new String[]{noteId})) {
            while (c.moveToNext()) paths.add(c.getString(0));
        }
        for (String path : paths) {
            try {
                if (encrypt) MediaFiles.encrypt(path, collectionId);
                else MediaFiles.decrypt(path);
            } catch (GeneralSecurityException e) {
                Log.w("NoteRepository", "could not convert media file " + path, e);
            }
        }
    }

    private Set<String> replaceMediaAssetsSync(SQLiteDatabase db, String noteId, List<NoteSegment> segments) {
        Set<String> oldMediaPaths = new HashSet<>();
        Cursor c = db.rawQuery(
                "SELECT file_path FROM note_segments WHERE note_id = ?", new String[]{noteId});
        try {
            while (c.moveToNext()) {
                if (!c.isNull(0)) oldMediaPaths.add(c.getString(0));
            }
        } finally {
            c.close();
        }

        db.delete("note_segments", "note_id = ?", new String[]{noteId});

        Set<String> newMediaPaths = new HashSet<>();
        long now = System.currentTimeMillis();
        for (NoteSegment segment : segments) {
            if (!segment.isMedia()) continue;

            ContentValues cv = new ContentValues();
            cv.put("id", segment.id != null ? segment.id : UUID.randomUUID().toString());
            cv.put("note_id", noteId);
            cv.put("type", segment.type());
            cv.put("file_path", segment.filePath());
            cv.put("created_at", now);

            if (segment instanceof ImageSegment) {
                cv.put("width", ((ImageSegment) segment).displayWidth);
            } else if (segment instanceof AudioSegment) {
                cv.put("duration_ms", ((AudioSegment) segment).durationMs);
            }

            db.insert("note_segments", null, cv);
            newMediaPaths.add(segment.filePath());
        }

        oldMediaPaths.removeAll(newMediaPaths);
        return oldMediaPaths;
    }

    // ── Search index ───────────────────────────────────────────────────────
    // Both helpers tolerate notes_fts being absent: AppDatabase skips creating it on SQLite
    // builds without FTS5, and search still works (in-memory filtering) without it.

    public interface OnSearchMatches {
        /** {@code null} means "no answer" — an unusable query, or a build without FTS5. Callers
         *  fall back to matching what they already hold in memory rather than showing nothing. */
        void onMatched(Set<String> noteIds);
    }

    /**
     * The ids of every indexed note matching {@code rawQuery}, title or body.
     *
     * <p>This is what the search bar was missing. Home matched a note's title and its *preview* —
     * the first line or two — so a word in the middle of a long note simply could not be found,
     * which is the one thing a search box is for. {@code notes_fts} has been kept current on every
     * save for a while; nothing ever asked it anything.
     *
     * <p>Locked collections stay out by construction: their notes are removed from the index when
     * the collection is locked, because the index stores the body as plain text. A note in a
     * collection that is open right now is therefore also unindexed until it is next saved — it
     * still matches on title, which is the same fallback an unsaved note gets.
     */
    public void searchNoteIds(String rawQuery, OnSearchMatches cb) {
        List<String> tokens = searchTokens(rawQuery);
        if (tokens.isEmpty()) {
            if (cb != null) executors.mainThread(() -> cb.onMatched(null));
            return;
        }
        executors.diskIO(() -> {
            Set<String> ids = searchNoteIdsSync(tokens);
            if (cb != null) executors.mainThread(() -> cb.onMatched(ids));
        });
    }

    /**
     * Ids of notes matching every token, from the index if there is one and by reading the bodies
     * if there isn't.
     *
     * <p>The scan is not a formality. FTS5 is a compile-time SQLite module and a fair number of
     * builds — this project's own emulator image among them — ship without it, which is why every
     * write to {@code notes_fts} is wrapped in a try/catch. Leaving the search to fall back on the
     * in-memory <em>preview</em> match on those devices would mean the feature quietly does nothing
     * exactly where it can't be noticed. Reading the bodies is slower and completely reliable, and
     * a note collection is small enough that the difference is not worth a feature that only works
     * on some phones.
     */
    private Set<String> searchNoteIdsSync(List<String> tokens) {
        SQLiteDatabase db = appDatabase.getWritableDatabase();
        Set<String> ids = new HashSet<>();
        try (Cursor c = db.rawQuery(
                "SELECT note_id FROM notes_fts WHERE notes_fts MATCH ?",
                new String[]{toFtsQuery(tokens)})) {
            while (c.moveToNext()) ids.add(c.getString(0));
            return ids;
        } catch (SQLiteException e) {
            Log.w("NoteRepository", "notes_fts unavailable; scanning bodies instead", e);
        }
        return scanBodiesSync(db, tokens);
    }

    /**
     * The no-index path: every readable note's body, flattened and matched in Java.
     *
     * <p>Locked collections are skipped rather than decrypted, matching the index's own rule — a
     * search that could reach into a shut collection would be a way to read it without unlocking
     * it, one word at a time.
     */
    private Set<String> scanBodiesSync(SQLiteDatabase db, List<String> tokens) {
        Set<String> ids = new HashSet<>();
        try (Cursor c = db.rawQuery(
                "SELECT n.id, n.title, n.content_blob FROM notes n "
                        + "LEFT JOIN collections c ON c.id = n.collection_id "
                        + "WHERE n.deleted_at IS NULL "
                        + "AND (c.biometric_locked IS NULL OR c.biometric_locked = 0)", null)) {
            while (c.moveToNext()) {
                String markdown = c.isNull(2)
                        ? "" : new String(c.getBlob(2), StandardCharsets.UTF_8);
                String haystack = ((c.isNull(1) ? "" : c.getString(1))
                        + "\n" + NoteDocument.toPlainText(markdown))
                        .toLowerCase(Locale.getDefault());
                boolean all = true;
                for (String token : tokens) {
                    if (!haystack.contains(token)) { all = false; break; }
                }
                if (all) ids.add(c.getString(0));
            }
        } catch (SQLiteException e) {
            Log.w("NoteRepository", "note body scan failed", e);
            return null;
        }
        return ids;
    }

    /**
     * What the user typed, reduced to the words worth matching.
     *
     * <p>The tokenizer indexes letters and digits, so anything else in a token can only fail to
     * match — stripping it is what stops "c++" becoming a syntax error rather than a search.
     */
    static List<String> searchTokens(String rawQuery) {
        List<String> tokens = new ArrayList<>();
        if (rawQuery == null) return tokens;
        for (String token : rawQuery.trim().split("\\s+")) {
            String cleaned = token.replaceAll("[^\\p{L}\\p{N}]", "")
                    .toLowerCase(Locale.getDefault());
            if (!cleaned.isEmpty()) tokens.add(cleaned);
        }
        return tokens;
    }

    /**
     * The tokens as something FTS5 will accept.
     *
     * <p>Raw input cannot be handed to {@code MATCH}: quotes, colons, asterisks and the bare words
     * AND/OR/NOT are all query syntax, so a stray quote throws rather than finding nothing. Each
     * token goes in as a quoted phrase — which disarms all of it — with a trailing {@code *} so the
     * search matches as you type rather than only on whole words. Tokens are ANDed, so a second
     * word narrows, which is what the scan above does too.
     */
    static String toFtsQuery(List<String> tokens) {
        StringBuilder match = new StringBuilder();
        for (String token : tokens) {
            if (match.length() > 0) match.append(' ');
            match.append('"').append(token).append("\"*");
        }
        return match.toString();
    }

    private void indexNoteSync(SQLiteDatabase db, String noteId, String title, String markdown) {
        try {
            db.delete("notes_fts", "note_id = ?", new String[]{noteId});
            ContentValues cv = new ContentValues();
            cv.put("note_id", noteId);
            cv.put("title", title);
            cv.put("body", NoteDocument.toPlainText(markdown));
            db.insert("notes_fts", null, cv);
        } catch (SQLiteException e) {
            Log.w("NoteRepository", "notes_fts unavailable, skipping index update", e);
        }
    }

    private void deleteFromIndexSync(SQLiteDatabase db, String noteId) {
        try {
            db.delete("notes_fts", "note_id = ?", new String[]{noteId});
        } catch (SQLiteException e) {
            Log.w("NoteRepository", "notes_fts unavailable, skipping index delete", e);
        }
    }
}
