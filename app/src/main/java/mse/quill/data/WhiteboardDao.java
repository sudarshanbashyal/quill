package mse.quill.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import mse.quill.model.Whiteboard;

/**
 * WhiteboardDao
 *
 * Handles reads/writes for the `whiteboards` table only.
 * One row per whiteboard session; the actual drawing data lives in `strokes`
 * (see StrokeDao), linked via whiteboard_id.
 *
 * Callers on a UI thread should go through {@link WhiteboardRepository} instead — it wraps the
 * same table in the app's shared AppExecutors pattern. This DAO is the synchronous layer both it
 * and WhiteboardFragment sit on.
 */
public class WhiteboardDao {

    private final AppDatabase db;

    public WhiteboardDao(AppDatabase db) {
        this.db = db;
    }

    /** Insert a new whiteboard row. Called once when a whiteboard is first created. */
    public void insert(Whiteboard wb) {
        ContentValues v = new ContentValues();
        v.put("id", wb.id);
        v.put("note_id", wb.noteId);
        v.put("title", wb.title);
        v.put("created_at", wb.createdAt);
        v.put("updated_at", wb.updatedAt);
        db.getWritableDatabase().insertWithOnConflict(
                "whiteboards", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Records that the canvas changed, so Home's Whiteboards section sorts by real recency. */
    public void touch(String id, long updatedAt) {
        ContentValues v = new ContentValues();
        v.put("updated_at", updatedAt);
        db.getWritableDatabase().update("whiteboards", v, "id = ?", new String[]{id});
    }

    /** Fetch a whiteboard by its id. Returns null if not found. */
    public Whiteboard getById(String id) {
        Cursor c = db.getReadableDatabase().query(
                "whiteboards", null,
                "id = ?", new String[]{id},
                null, null, null);
        Whiteboard wb = null;
        if (c.moveToFirst()) {
            wb = fromCursor(c);
        }
        c.close();
        return wb;
    }

    /** Fetch the whiteboard belonging to a given note (a note has at most one whiteboard). */
    public Whiteboard getByNoteId(String noteId) {
        Cursor c = db.getReadableDatabase().query(
                "whiteboards", null,
                "note_id = ?", new String[]{noteId},
                null, null, null);
        Whiteboard wb = null;
        if (c.moveToFirst()) {
            wb = fromCursor(c);
        }
        c.close();
        return wb;
    }

    static Whiteboard fromCursor(Cursor c) {
        Whiteboard wb = new Whiteboard();
        wb.id        = c.getString(c.getColumnIndexOrThrow("id"));
        wb.noteId    = c.getString(c.getColumnIndexOrThrow("note_id"));
        wb.title     = c.getString(c.getColumnIndexOrThrow("title"));
        wb.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        wb.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return wb;
    }
}
