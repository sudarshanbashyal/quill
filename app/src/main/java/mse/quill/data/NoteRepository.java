package mse.quill.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import mse.quill.data.model.Note;
import mse.quill.data.model.Tag;
import mse.quill.data.serialization.SpanSerializer;
import mse.quill.ui.notes.editor.model.AudioSegment;
import mse.quill.ui.notes.editor.model.ImageSegment;
import mse.quill.ui.notes.editor.model.NoteSegment;
import mse.quill.ui.notes.editor.model.TextSegment;

public class NoteRepository {

    public static final int MAX_PINNED_NOTES = 3;

    public interface OnNoteCreated { void onCreated(String noteId); }
    public interface OnNoteLoaded { void onLoaded(Note note, List<NoteSegment> segments); }
    public interface OnNotesLoaded { void onLoaded(List<Note> notes); }
    public interface OnPinResult { void onPinned(); void onLimitReached(); }

    private final AppDatabase appDatabase;
    private final AppExecutors executors;

    public NoteRepository(Context context) {
        this.appDatabase = AppDatabase.getInstance(context.getApplicationContext());
        this.executors = AppExecutors.getInstance();
    }

    public void createNote(String title, String collectionId, OnNoteCreated cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();

            ContentValues cv = new ContentValues();
            cv.put("id", id);
            if (collectionId != null) cv.put("collection_id", collectionId);
            cv.put("title", title);
            cv.put("created_at", now);
            cv.put("updated_at", now);
            db.insert("notes", null, cv);

            if (cb != null) executors.mainThread(() -> cb.onCreated(id));
        });
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

            db.beginTransaction();
            try {
                ContentValues cv = new ContentValues();
                cv.put("title", title);
                cv.put("updated_at", System.currentTimeMillis());
                db.update("notes", cv, "id = ?", new String[]{noteId});

                orphanedMediaPaths = replaceSegmentsSync(db, noteId, segments);

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

    public void deleteNote(String noteId, Runnable onDeleted) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("deleted_at", System.currentTimeMillis());
            db.update("notes", cv, "id = ?", new String[]{noteId});
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
                        "(SELECT s.text_content FROM note_segments s " +
                        " WHERE s.note_id = n.id AND s.type = " + NoteSegment.TYPE_TEXT +
                        " ORDER BY s.position ASC LIMIT 1) AS preview_blob " +
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
                note.preview = c.isNull(7) ? "" : SpanSerializer.fromBytes(c.getBlob(7)).toString().trim();
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

    private List<NoteSegment> getSegmentsSync(SQLiteDatabase db, String noteId) {
        Cursor c = db.rawQuery(
                "SELECT type, text_content, file_path, width, duration_ms FROM note_segments " +
                        "WHERE note_id = ? ORDER BY position ASC",
                new String[]{noteId});
        try {
            List<NoteSegment> segments = new ArrayList<>();
            while (c.moveToNext()) {
                int type = c.getInt(0);
                if (type == NoteSegment.TYPE_IMAGE) {
                    ImageSegment segment = new ImageSegment(c.getString(2));
                    if (!c.isNull(3)) segment.displayWidth = c.getInt(3);
                    segments.add(segment);
                } else if (type == NoteSegment.TYPE_AUDIO) {
                    int durationMs = c.isNull(4) ? 0 : c.getInt(4);
                    segments.add(new AudioSegment(c.getString(2), durationMs));
                } else {
                    segments.add(new TextSegment(SpanSerializer.fromBytes(c.getBlob(1))));
                }
            }
            return segments;
        } finally {
            c.close();
        }
    }

    /** Deletes and reinserts all segments for a note inside the caller's transaction.
     *  Returns the file paths of image segments that existed before but are no longer
     *  referenced, so the caller can delete those files once the transaction has committed. */
    private Set<String> replaceSegmentsSync(SQLiteDatabase db, String noteId, List<NoteSegment> segments) {
        Set<String> oldMediaPaths = new HashSet<>();
        Cursor c = db.rawQuery(
                "SELECT file_path FROM note_segments WHERE note_id = ? AND type IN (?, ?)",
                new String[]{noteId, String.valueOf(NoteSegment.TYPE_IMAGE), String.valueOf(NoteSegment.TYPE_AUDIO)});
        try {
            while (c.moveToNext()) oldMediaPaths.add(c.getString(0));
        } finally {
            c.close();
        }

        db.delete("note_segments", "note_id = ?", new String[]{noteId});

        Set<String> newMediaPaths = new HashSet<>();
        long now = System.currentTimeMillis();
        int position = 0;
        for (NoteSegment segment : segments) {
            ContentValues cv = new ContentValues();
            cv.put("id", segment.id != null ? segment.id : UUID.randomUUID().toString());
            cv.put("note_id", noteId);
            cv.put("position", position++);
            cv.put("created_at", now);

            if (segment instanceof ImageSegment) {
                ImageSegment image = (ImageSegment) segment;
                cv.put("type", NoteSegment.TYPE_IMAGE);
                cv.put("file_path", image.filePath);
                cv.put("width", image.displayWidth);
                newMediaPaths.add(image.filePath);
            } else if (segment instanceof AudioSegment) {
                AudioSegment audio = (AudioSegment) segment;
                cv.put("type", NoteSegment.TYPE_AUDIO);
                cv.put("file_path", audio.filePath);
                cv.put("duration_ms", audio.durationMs);
                newMediaPaths.add(audio.filePath);
            } else if (segment instanceof TextSegment) {
                TextSegment text = (TextSegment) segment;
                cv.put("type", NoteSegment.TYPE_TEXT);
                cv.put("text_content", SpanSerializer.toBytes(text.content));
            } else {
                continue; // unsupported segment type
            }
            db.insert("note_segments", null, cv);
        }

        oldMediaPaths.removeAll(newMediaPaths);
        return oldMediaPaths;
    }
}
