package mse.quill.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import mse.quill.model.Whiteboard;

/**
 * Async, callback-based access to the `whiteboards` table, following the same shape as
 * NoteRepository / CollectionRepository and routing every query through the shared
 * AppExecutors.diskIO() thread.
 *
 * WhiteboardFragment still talks to {@link WhiteboardDao} / {@link StrokeDao} directly on its own
 * threads (see memory/requirements.md Epic A); this repository is what the Home screen uses.
 */
public class WhiteboardRepository {

    public interface OnWhiteboardCreated { void onCreated(String whiteboardId); }
    public interface OnWhiteboardsLoaded { void onLoaded(List<Whiteboard> whiteboards); }

    private final AppDatabase appDatabase;
    private final AppExecutors executors;

    public WhiteboardRepository(Context context) {
        this.appDatabase = AppDatabase.getInstance(context.getApplicationContext());
        this.executors = AppExecutors.getInstance();
    }

    /** Creates an empty standalone board (noteId null) or one attached to a note. */
    public void createWhiteboard(String title, String noteId, OnWhiteboardCreated cb) {
        executors.diskIO(() -> {
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();

            ContentValues cv = new ContentValues();
            cv.put("id", id);
            cv.put("note_id", noteId);
            cv.put("title", title);
            cv.put("created_at", now);
            cv.put("updated_at", now);
            appDatabase.getWritableDatabase().insert("whiteboards", null, cv);

            if (cb != null) executors.mainThread(() -> cb.onCreated(id));
        });
    }

    public void renameWhiteboard(String id, String newTitle, Runnable onDone) {
        executors.diskIO(() -> {
            ContentValues cv = new ContentValues();
            cv.put("title", newTitle);
            appDatabase.getWritableDatabase().update("whiteboards", cv, "id = ?", new String[]{id});
            if (onDone != null) executors.mainThread(onDone);
        });
    }

    /**
     * Deletes the board and its strokes. Hard delete, matching how collections are removed —
     * there is no trash surface for whiteboards, so a soft-deleted board would just be
     * unreachable rows. The strokes go first: they carry a foreign key onto this row.
     */
    public void deleteWhiteboard(String id, Runnable onDone) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete("strokes", "whiteboard_id = ?", new String[]{id});
                db.delete("whiteboards", "id = ?", new String[]{id});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            if (onDone != null) executors.mainThread(onDone);
        });
    }

    /** Most recently edited first, so Home's section reads like the notes list. */
    public void loadWhiteboards(OnWhiteboardsLoaded cb) {
        executors.diskIO(() -> {
            Cursor c = appDatabase.getWritableDatabase().rawQuery(
                    "SELECT w.id, w.note_id, w.title, w.created_at, w.updated_at, " +
                            "(SELECT COUNT(*) FROM strokes s WHERE s.whiteboard_id = w.id) AS stroke_count " +
                            "FROM whiteboards w ORDER BY w.updated_at DESC, w.created_at DESC",
                    null);
            List<Whiteboard> whiteboards = new ArrayList<>();
            try {
                while (c.moveToNext()) {
                    Whiteboard wb = new Whiteboard();
                    wb.id = c.getString(0);
                    wb.noteId = c.getString(1);
                    wb.title = c.getString(2);
                    wb.createdAt = c.getLong(3);
                    wb.updatedAt = c.isNull(4) ? wb.createdAt : c.getLong(4);
                    wb.strokeCount = c.getInt(5);
                    whiteboards.add(wb);
                }
            } finally {
                c.close();
            }
            if (cb != null) executors.mainThread(() -> cb.onLoaded(whiteboards));
        });
    }
}
