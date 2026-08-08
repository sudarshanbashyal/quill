package mse.quill.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import mse.quill.data.model.Note;
import mse.quill.data.model.Tag;
import mse.quill.data.serialization.NoteDocument;
import mse.quill.ui.notes.editor.model.AudioSegment;
import mse.quill.ui.notes.editor.model.ImageSegment;
import mse.quill.ui.notes.editor.model.NoteSegment;

public class NoteRepository {

    public static final int MAX_PINNED_NOTES = 3;

    public interface OnNoteLoaded { void onLoaded(Note note, List<NoteSegment> segments); }
    public interface OnNotesLoaded { void onLoaded(List<Note> notes); }
    public interface OnPinResult { void onPinned(); void onLimitReached(); }

    private final AppDatabase appDatabase;
    private final AppExecutors executors;

    public NoteRepository(Context context) {
        this.appDatabase = AppDatabase.getInstance(context.getApplicationContext());
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

            if (onCreated != null) executors.mainThread(onCreated);
        });
    }

    /** Mints an id for a note that is about to be created. */
    public static String newNoteId() {
        return UUID.randomUUID().toString();
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
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            Set<String> orphanedMediaPaths;

            String markdown = NoteDocument.toMarkdown(segments);

            // Nothing to write if nothing changed. The editor auto-saves on pause whether or not
            // anything was typed, and this used to bump updated_at regardless — so merely opening
            // a note and backing out of it reported "Updated now" on Home and jumped it to the top
            // of the list. Checked here rather than in the editor because the markdown is already
            // built on this thread, and because it fixes every caller at once.
            if (nothingToWrite(db, noteId, title, markdown)) {
                if (onSaved != null) executors.mainThread(onSaved);
                return;
            }

            db.beginTransaction();
            try {
                ContentValues cv = new ContentValues();
                cv.put("title", title);
                cv.put("content_blob", markdown.getBytes(StandardCharsets.UTF_8));
                cv.put("updated_at", System.currentTimeMillis());
                db.update("notes", cv, "id = ?", new String[]{noteId});

                orphanedMediaPaths = replaceMediaAssetsSync(db, noteId, segments);
                indexNoteSync(db, noteId, title, markdown);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            for (String path : orphanedMediaPaths) {
                new File(path).delete();
            }

            if (onSaved != null) executors.mainThread(onSaved);
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
    private boolean nothingToWrite(SQLiteDatabase db, String noteId, String title, String markdown) {
        Cursor c = db.query("notes", new String[]{"title", "content_blob"},
                "id = ?", new String[]{noteId}, null, null, null);
        try {
            if (!c.moveToFirst()) return false;   // no row yet: this save is what creates it
            String storedTitle = c.getString(0);
            byte[] storedBlob = c.getBlob(1);
            String storedMarkdown = storedBlob == null
                    ? "" : new String(storedBlob, StandardCharsets.UTF_8);
            if (!Objects.equals(storedTitle, title) || !storedMarkdown.equals(markdown)) return false;
        } finally {
            c.close();
        }
        return isIndexed(db, noteId);
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
        });
    }

    public void assignCollection(String noteId, String collectionId, Runnable onDone) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("collection_id", collectionId);
            db.update("notes", cv, "id = ?", new String[]{noteId});
            if (onDone != null) executors.mainThread(onDone);
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
            if (cb != null) executors.mainThread(cb::onPinned);
        });
    }

    public void unpinNote(String noteId, Runnable onDone) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.putNull("pinned_at");
            db.update("notes", cv, "id = ?", new String[]{noteId});
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
     * @return null if the note doesn't exist (or was soft-deleted).
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

    private Note getNoteSync(SQLiteDatabase db, String noteId) {
        Cursor c = db.rawQuery(
                "SELECT id, collection_id, title, created_at, updated_at, deleted_at, pinned_at " +
                        "FROM notes WHERE id = ? AND deleted_at IS NULL",
                new String[]{noteId});
        try {
            if (!c.moveToFirst()) return null;
            return readNote(c);
        } finally {
            c.close();
        }
    }

    private List<Note> getAllNotesSync(SQLiteDatabase db, String filter, boolean pinnedOnly) {
        StringBuilder sql = new StringBuilder(
                "SELECT n.id, n.collection_id, n.title, n.created_at, n.updated_at, n.deleted_at, n.pinned_at, " +
                        "n.content_blob " +
                        "FROM notes n WHERE n.deleted_at IS NULL");

        List<String> args = new ArrayList<>();
        if (filter != null) {
            sql.append(" AND n.collection_id = ?");
            args.add(filter);
        }
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
                note.preview = c.isNull(7)
                        ? ""
                        : NoteDocument.toPreview(new String(c.getBlob(7), StandardCharsets.UTF_8));
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
        Cursor c = db.rawQuery("SELECT content_blob FROM notes WHERE id = ?", new String[]{noteId});
        try {
            if (!c.moveToFirst() || c.isNull(0)) return "";
            return new String(c.getBlob(0), StandardCharsets.UTF_8);
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
