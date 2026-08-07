package mse.quill.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

import mse.quill.data.model.WhiteboardText;

/**
 * Reads and writes the typed text placed on a whiteboard.
 *
 * <p>Deliberately the same shape as {@link StrokeRepository} — insert, read a board's worth, delete one,
 * delete them all — because a text item behaves exactly like a stroke: added, undone, or cleared,
 * never edited. Kept as a lower-level DAO alongside StrokeRepository rather than a repository, matching
 * how {@code WhiteboardFragment} already talks to the database (see note.md, Epic A).
 */
public class WhiteboardTextRepository {

    private final AppDatabase db;

    public WhiteboardTextRepository(AppDatabase db) {
        this.db = db;
    }

    /** Insert or replace a text item (REPLACE means re-saving the same id overwrites it). */
    public void insert(WhiteboardText item) {
        ContentValues v = new ContentValues();
        v.put("id", item.id);
        v.put("whiteboard_id", item.whiteboardId);
        v.put("author_id", item.authorId);
        v.put("x", item.x);
        v.put("y", item.y);
        v.put("text", item.text);
        v.put("color", item.color);
        v.put("size", item.size);
        v.put("created_at", item.createdAt);

        db.getWritableDatabase().insertWithOnConflict(
                "whiteboard_texts", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Fetch every text item on a whiteboard, oldest first, so undo order survives reopening. */
    public List<WhiteboardText> getByWhiteboard(String whiteboardId) {
        List<WhiteboardText> results = new ArrayList<>();
        Cursor c = db.getReadableDatabase().query(
                "whiteboard_texts", null,
                "whiteboard_id = ?", new String[]{whiteboardId},
                null, null, "created_at ASC");
        try {
            while (c.moveToNext()) results.add(fromCursor(c));
        } finally {
            c.close();
        }
        return results;
    }

    /** Remove one text item (used by Undo). */
    public void delete(String id) {
        db.getWritableDatabase().delete("whiteboard_texts", "id = ?", new String[]{id});
    }

    /** Remove every text item on a whiteboard (used by Clear, and when a board is deleted). */
    public void deleteAllForWhiteboard(String whiteboardId) {
        db.getWritableDatabase().delete(
                "whiteboard_texts", "whiteboard_id = ?", new String[]{whiteboardId});
    }

    private WhiteboardText fromCursor(Cursor c) {
        WhiteboardText item = new WhiteboardText();
        item.id           = c.getString(c.getColumnIndexOrThrow("id"));
        item.whiteboardId = c.getString(c.getColumnIndexOrThrow("whiteboard_id"));
        item.authorId     = c.getString(c.getColumnIndexOrThrow("author_id"));
        item.x            = c.getFloat(c.getColumnIndexOrThrow("x"));
        item.y            = c.getFloat(c.getColumnIndexOrThrow("y"));
        item.text         = c.getString(c.getColumnIndexOrThrow("text"));
        item.color        = c.getInt(c.getColumnIndexOrThrow("color"));
        item.size         = c.getFloat(c.getColumnIndexOrThrow("size"));
        item.createdAt    = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return item;
    }
}
