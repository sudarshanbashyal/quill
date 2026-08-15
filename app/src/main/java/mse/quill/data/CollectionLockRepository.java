package mse.quill.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

import mse.quill.data.serialization.NoteDocument;
import mse.quill.security.CollectionCrypto;
import mse.quill.security.CollectionLock;

/**
 * Turning a collection's lock on and off — the two migrations that move its notes between
 * plaintext and ciphertext.
 *
 * <p><b>All of it, or none of it.</b> Each direction converts every note in the collection and
 * flips {@code biometric_locked} in a single transaction, because those two facts are the same
 * fact: {@link NoteCrypto} decides whether a row is encrypted by reading that column, so a run
 * that updated some rows and then failed would leave the rest permanently misread — plaintext
 * decrypted as ciphertext, or the reverse. The conversion is therefore done in memory first and
 * written only once it has all succeeded.
 *
 * <p>Both directions are guarded by authentication before they are called (see
 * {@code CollectionDialogs}), which is also what opens the Keystore key's window for the work
 * itself.
 */
public class CollectionLockRepository {

    private static final String TAG = "CollectionLockRepo";

    public interface Callback {
        void onDone();

        /** @param needsAuth true if the key wanted authentication rather than being unusable. */
        void onFailed(boolean needsAuth);
    }

    private final AppDatabase appDatabase;
    private final AppExecutors executors;
    /** Held for {@link WearNoteListPublisher}, which needs a Context to resolve untitled names. */
    private final Context appContext;

    public CollectionLockRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.appDatabase = AppDatabase.getInstance(appContext);
        this.executors = AppExecutors.getInstance();
    }

    /** One note's text, held in memory between reading it and writing the converted version. */
    private static final class Row {
        final String id;
        final String title;
        final byte[] body;

        Row(String id, String title, byte[] body) {
            this.id = id;
            this.title = title;
            this.body = body;
        }
    }

    /**
     * Locks the collection: a fresh key, every note encrypted in place, and every note dropped
     * from the search index.
     *
     * <p>The index removal is not incidental. {@code notes_fts} holds the body as plain text, so
     * leaving those rows would keep a fully readable, unencrypted copy of the collection in a
     * table the lock doesn't cover — the encryption would be decorative.
     */
    public void lock(String collectionId, Callback cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            try {
                CollectionCrypto.createKey(collectionId);

                List<Row> converted = new ArrayList<>();
                List<Row> plain = readNotes(db, collectionId);
                for (Row row : plain) {
                    String markdown = row.body == null
                            ? "" : new String(row.body, StandardCharsets.UTF_8);
                    converted.add(new Row(row.id,
                            NoteCrypto.encryptTitle(collectionId, row.title),
                            NoteCrypto.encryptBody(collectionId, markdown)));
                }

                writeNotes(db, collectionId, converted, true);
                // The last moment these bodies are readable. Any whiteboard embedded in them has to
                // be on record now, or the boards of a note nobody can read stay listed on Home.
                relinkWhiteboards(db, plain);

                // The user just authenticated to get here, so the collection opens rather than
                // immediately asking again for something they are plainly in the middle of.
                CollectionLock.markUnlocked(collectionId);
                if (cb != null) executors.mainThread(cb::onDone);

                // These titles are no longer allowed off the device — see WearNoteListPublisher,
                // which excludes every encrypted collection whether it is open or shut. Without
                // this the watch kept listing them until something else happened to republish,
                // which is the encryption having been applied everywhere except the one surface
                // that had already carried the names off the phone.
                WearNoteListPublisher.publishSync(appContext);
            } catch (GeneralSecurityException e) {
                Log.w(TAG, "could not lock collection " + collectionId, e);
                // The key is useless without the rows it was made for; leaving it behind would
                // make the *next* attempt look like a re-lock of something already locked.
                deleteKeyQuietly(collectionId);
                boolean needsAuth = e instanceof CollectionCrypto.NeedsAuthException;
                if (cb != null) executors.mainThread(() -> cb.onFailed(needsAuth));
            }
        });
    }

    /**
     * Unlocks the collection for good: every note decrypted back to plaintext, re-indexed for
     * search, the flag cleared and the key destroyed.
     *
     * <p>The key is deleted last, after the transaction has committed. Destroying it first would
     * turn a failed write into unrecoverable data — the ciphertext would still be on disk with
     * nothing left that could read it.
     */
    public void unlock(String collectionId, Callback cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            try {
                List<Row> converted = new ArrayList<>();
                for (Row row : readNotes(db, collectionId)) {
                    String markdown = NoteCrypto.decryptBody(collectionId, row.body);
                    converted.add(new Row(row.id,
                            NoteCrypto.decryptTitle(collectionId, row.title),
                            markdown == null ? null : markdown.getBytes(StandardCharsets.UTF_8)));
                }

                writeNotes(db, collectionId, converted, false);
                // Cheap, and it repairs a collection that was already locked when the links table
                // was introduced — the migration couldn't read those bodies, this can.
                relinkWhiteboards(db, converted);

                deleteKeyQuietly(collectionId);
                CollectionLock.relock(collectionId);
                if (cb != null) executors.mainThread(cb::onDone);

                // The other direction: these notes are ordinary again and may rejoin the watch's
                // pickers. Unlike the lock, nothing is at stake in being late — but a list that is
                // rebuilt on one edge and not the other is a list nobody can reason about.
                WearNoteListPublisher.publishSync(appContext);
            } catch (GeneralSecurityException e) {
                Log.w(TAG, "could not unlock collection " + collectionId, e);
                boolean needsAuth = e instanceof CollectionCrypto.NeedsAuthException;
                if (cb != null) executors.mainThread(() -> cb.onFailed(needsAuth));
            }
        });
    }

    /**
     * Forgets a collection whose key is gone: clears the flag and drops the notes, which is all
     * that can honestly be done with ciphertext nothing can decrypt.
     *
     * <p>Offered rather than performed automatically, and only after the user has been told what
     * it means — the alternative is keeping rows that will never be readable again, which is a
     * legitimate choice if they'd rather not admit it yet.
     */
    public void discardUnreadable(String collectionId, Runnable onDone) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            db.beginTransaction();
            try {
                List<Row> rows = readNotes(db, collectionId);
                for (Row row : rows) {
                    db.delete("note_segments", "note_id = ?", new String[]{row.id});
                    db.delete("note_whiteboards", "note_id = ?", new String[]{row.id});
                    db.delete("flashcards", "note_id = ?", new String[]{row.id});
                    db.delete("notes", "id = ?", new String[]{row.id});
                    deleteFromIndex(db, row.id);
                }
                ContentValues cv = new ContentValues();
                cv.put("biometric_locked", 0);
                db.update("collections", cv, "id = ?", new String[]{collectionId});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            deleteKeyQuietly(collectionId);
            CollectionLock.relock(collectionId);
            if (onDone != null) executors.mainThread(onDone);

            // These notes are gone for good, not soft-deleted, so the watch must stop offering
            // them. Same rule as everywhere else that changes what belongs on the list.
            WearNoteListPublisher.publishSync(appContext);
        });
    }

    // ---------- Internals ----------

    /** Soft-deleted notes are included on purpose: they are still in the collection and still
     *  readable if restored from the trash, so they have to be converted with everything else. */
    private List<Row> readNotes(SQLiteDatabase db, String collectionId) {
        List<Row> rows = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT id, title, content_blob FROM notes WHERE collection_id = ?",
                new String[]{collectionId})) {
            while (c.moveToNext()) {
                rows.add(new Row(c.getString(0), c.isNull(1) ? null : c.getString(1),
                        c.isNull(2) ? null : c.getBlob(2)));
            }
        }
        return rows;
    }

    private void writeNotes(SQLiteDatabase db, String collectionId, List<Row> rows, boolean locked) {
        db.beginTransaction();
        try {
            for (Row row : rows) {
                ContentValues cv = new ContentValues();
                cv.put("title", row.title);
                cv.put("content_blob", row.body);
                // updated_at is deliberately left alone: locking a collection is not editing its
                // notes, and bumping it would reorder every list by an act that changed no text.
                db.update("notes", cv, "id = ?", new String[]{row.id});

                if (locked) {
                    deleteFromIndex(db, row.id);
                    // Flashcards hold the question and answer as their own plaintext columns,
                    // copied out of the note's Q&A blocks. Encrypting the note while leaving those
                    // behind would keep a readable copy of its content in a table the lock doesn't
                    // reach — the same reason the FTS rows go. They aren't lost so much as reset:
                    // re-syncing the deck after unlocking rebuilds every card from the blocks it
                    // came from. What genuinely goes is the SM-2 schedule, which is why the
                    // confirmation dialog says so before any of this runs.
                    db.delete("flashcards", "note_id = ?", new String[]{row.id});
                } else {
                    reindex(db, row.id, row.title, row.body);
                }
            }

            ContentValues flag = new ContentValues();
            flag.put("biometric_locked", locked ? 1 : 0);
            db.update("collections", flag, "id = ?", new String[]{collectionId});

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Rebuilds the whiteboard links for rows whose {@code body} is plaintext Markdown. */
    private void relinkWhiteboards(SQLiteDatabase db, List<Row> plainRows) {
        db.beginTransaction();
        try {
            for (Row row : plainRows) {
                WhiteboardLinks.replace(db, row.id, row.body == null
                        ? "" : new String(row.body, StandardCharsets.UTF_8));
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void reindex(SQLiteDatabase db, String noteId, String title, byte[] body) {
        try {
            db.delete("notes_fts", "note_id = ?", new String[]{noteId});
            ContentValues cv = new ContentValues();
            cv.put("note_id", noteId);
            cv.put("title", title);
            cv.put("body", NoteDocument.toPlainText(
                    body == null ? "" : new String(body, StandardCharsets.UTF_8)));
            db.insert("notes_fts", null, cv);
        } catch (SQLiteException e) {
            Log.w(TAG, "notes_fts unavailable, skipping reindex", e);
        }
    }

    private void deleteFromIndex(SQLiteDatabase db, String noteId) {
        try {
            db.delete("notes_fts", "note_id = ?", new String[]{noteId});
        } catch (SQLiteException e) {
            Log.w(TAG, "notes_fts unavailable, skipping index delete", e);
        }
    }

    private void deleteKeyQuietly(String collectionId) {
        try {
            CollectionCrypto.deleteKey(collectionId);
        } catch (GeneralSecurityException e) {
            Log.w(TAG, "could not delete key for " + collectionId, e);
        }
    }
}
