package mse.quill.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import mse.quill.data.model.Note;
import mse.quill.data.serialization.SpanSerializer;
import mse.quill.ui.notes.editor.model.ImageSegment;
import mse.quill.ui.notes.editor.model.NoteSegment;
import mse.quill.ui.notes.editor.model.TextSegment;

public class NoteRepository {

    /** Pass this as the collection filter to loadNotes() to mean "no collection". */
    public static final String UNCATEGORIZED = "";

    public interface OnNoteCreated { void onCreated(String noteId); }
    public interface OnNoteLoaded { void onLoaded(Note note, List<NoteSegment> segments); }
    public interface OnNotesLoaded { void onLoaded(List<Note> notes); }

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
            Set<String> orphanedImagePaths;

            db.beginTransaction();
            try {
                ContentValues cv = new ContentValues();
                cv.put("title", title);
                cv.put("updated_at", System.currentTimeMillis());
                db.update("notes", cv, "id = ?", new String[]{noteId});

                orphanedImagePaths = replaceSegmentsSync(db, noteId, segments);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            for (String path : orphanedImagePaths) {
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

    /** filter: null = all notes, UNCATEGORIZED = notes with no collection, else a collection id. */
    public void loadNotes(String filter, OnNotesLoaded cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            List<Note> notes = getAllNotesSync(db, filter);
            if (cb != null) executors.mainThread(() -> cb.onLoaded(notes));
        });
    }

    // ── Sync helpers (must run on the diskIO executor) ──────────────────────

    private Note getNoteSync(SQLiteDatabase db, String noteId) {
        Cursor c = db.rawQuery(
                "SELECT id, collection_id, title, created_at, updated_at, deleted_at " +
                        "FROM notes WHERE id = ? AND deleted_at IS NULL",
                new String[]{noteId});
        try {
            if (!c.moveToFirst()) return null;
            Note note = new Note();
            note.id = c.getString(0);
            note.collectionId = c.isNull(1) ? null : c.getString(1);
            note.title = c.getString(2);
            note.createdAt = c.getLong(3);
            note.updatedAt = c.getLong(4);
            note.deletedAt = c.isNull(5) ? null : c.getLong(5);
            return note;
        } finally {
            c.close();
        }
    }

    private List<Note> getAllNotesSync(SQLiteDatabase db, String filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT n.id, n.collection_id, n.title, n.created_at, n.updated_at, n.deleted_at, " +
                        "(SELECT s.text_content FROM note_segments s " +
                        " WHERE s.note_id = n.id AND s.type = " + NoteSegment.TYPE_TEXT +
                        " ORDER BY s.position ASC LIMIT 1) AS preview_blob " +
                        "FROM notes n WHERE n.deleted_at IS NULL");

        List<String> args = new ArrayList<>();
        if (filter == null) {
            // no extra filter — all notes
        } else if (filter.equals(UNCATEGORIZED)) {
            sql.append(" AND n.collection_id IS NULL");
        } else {
            sql.append(" AND n.collection_id = ?");
            args.add(filter);
        }
        sql.append(" ORDER BY n.updated_at DESC");

        Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]));
        try {
            List<Note> notes = new ArrayList<>();
            while (c.moveToNext()) {
                Note note = new Note();
                note.id = c.getString(0);
                note.collectionId = c.isNull(1) ? null : c.getString(1);
                note.title = c.getString(2);
                note.createdAt = c.getLong(3);
                note.updatedAt = c.getLong(4);
                note.deletedAt = c.isNull(5) ? null : c.getLong(5);
                note.preview = c.isNull(6) ? "" : SpanSerializer.fromBytes(c.getBlob(6)).toString().trim();
                notes.add(note);
            }
            return notes;
        } finally {
            c.close();
        }
    }

    private List<NoteSegment> getSegmentsSync(SQLiteDatabase db, String noteId) {
        Cursor c = db.rawQuery(
                "SELECT type, text_content, file_path, width FROM note_segments " +
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
        Set<String> oldImagePaths = new HashSet<>();
        Cursor c = db.rawQuery(
                "SELECT file_path FROM note_segments WHERE note_id = ? AND type = ?",
                new String[]{noteId, String.valueOf(NoteSegment.TYPE_IMAGE)});
        try {
            while (c.moveToNext()) oldImagePaths.add(c.getString(0));
        } finally {
            c.close();
        }

        db.delete("note_segments", "note_id = ?", new String[]{noteId});

        Set<String> newImagePaths = new HashSet<>();
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
                newImagePaths.add(image.filePath);
            } else if (segment instanceof TextSegment) {
                TextSegment text = (TextSegment) segment;
                cv.put("type", NoteSegment.TYPE_TEXT);
                cv.put("text_content", SpanSerializer.toBytes(text.content));
            } else {
                continue; // unsupported segment type (e.g. future audio segments)
            }
            db.insert("note_segments", null, cv);
        }

        oldImagePaths.removeAll(newImagePaths);
        return oldImagePaths;
    }
}
