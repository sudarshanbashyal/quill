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
        db.getWritableDatabase().insertWithOnConflict(
                "whiteboards", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Fetch a whiteboard by its id. Returns null if not found. */
    public Whiteboard getById(String id) {
        Cursor c = db.getReadableDatabase().query(
                "whiteboards", null,
                "id = ?", new String[]{id},
                null, null, null);
        Whiteboard wb = null;
        if (c.moveToFirst()) {
            wb = new Whiteboard();
            wb.id     = c.getString(c.getColumnIndexOrThrow("id"));
            wb.noteId = c.getString(c.getColumnIndexOrThrow("note_id"));
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
            wb = new Whiteboard();
            wb.id     = c.getString(c.getColumnIndexOrThrow("id"));
            wb.noteId = c.getString(c.getColumnIndexOrThrow("note_id"));
        }
        c.close();
        return wb;
    }
}