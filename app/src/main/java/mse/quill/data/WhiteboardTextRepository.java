package mse.quill.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

import mse.quill.data.model.WhiteboardText;

/**
 * Reads and writes the typed text placed on a whiteboard.
 *
 * <p>Deliberately the same shape as {@link StrokeRepository}, down to the async/{@code Sync} split
 * — insert, read a board's worth, delete one, delete them all — because a text item behaves exactly
 * like a stroke: added, undone, or cleared, never edited. See {@link StrokeRepository} for why the
 * two surfaces exist.
 */
public class WhiteboardTextRepository {

    private final AppDatabase db;
    private final AppExecutors executors = AppExecutors.getInstance();

    public WhiteboardTextRepository(Context context) {
        this.db = AppDatabase.getInstance(context.getApplicationContext());
    }

    // ── Async: for the UI thread ──────────────────────────────────────────────

    /** Insert or replace a text item, off the caller's thread. */
    public void insert(WhiteboardText item) {
        executors.diskIO(() -> insertSync(item));
    }

    /** Remove one text item (used by Undo), off the caller's thread. */
    public void delete(String id) {
        executors.diskIO(() -> deleteSync(id));
    }

    /** Remove every text item on a whiteboard (used by Clear), off the caller's thread. */
    public void deleteAllForWhiteboard(String whiteboardId) {
        executors.diskIO(() -> deleteAllForWhiteboardSync(whiteboardId));
    }

    // ── Blocking: for callers already on the disk thread ──────────────────────

    /** Insert or replace a text item (REPLACE means re-saving the same id overwrites it). */
    public void insertSync(WhiteboardText item) {
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
    public List<WhiteboardText> getByWhiteboardSync(String whiteboardId) {
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
    public void deleteSync(String id) {
        db.getWritableDatabase().delete("whiteboard_texts", "id = ?", new String[]{id});
    }

    /** Remove every text item on a whiteboard (used by Clear, and when a board is deleted). */
    public void deleteAllForWhiteboardSync(String whiteboardId) {
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
