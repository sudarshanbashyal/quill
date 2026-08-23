package mse.quill.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.PointF;

import mse.quill.data.model.Stroke;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads and writes the {@code strokes} table.
 *
 * <p>Points are stored as a BLOB: each point is 8 bytes (4 bytes float x, 4 bytes float y), packed
 * sequentially. {@code serializePoints()}/{@code deserializePoints()} convert between this byte
 * format and the {@code List<PointF>} {@code WhiteboardView} uses.
 *
 * <p><b>Two surfaces, and the names say which is which.</b> The {@code Sync} methods block and are
 * for callers already on a background thread — the importers, the bundle writer, the thumbnailer.
 * The unsuffixed ones hand the same work to {@link AppExecutors#diskIO}, and are what the UI calls.
 * Until 2026-08-23 only the blocking half existed and {@code WhiteboardFragment} wrapped each call
 * in a {@code new Thread}, which put every stroke on a connection of its own: unordered against
 * each other (a fast undo could delete a stroke its insert had not reached yet) and contending for
 * the write lock that the single shared disk thread exists to serialize.
 */
public class StrokeRepository {

    private final AppDatabase db;
    private final AppExecutors executors = AppExecutors.getInstance();

    public StrokeRepository(AppDatabase db) {
        this.db = db;
    }

    // ── Async: for the UI thread ──────────────────────────────────────────────

    /** Insert or replace a stroke, off the caller's thread. */
    public void insertStroke(Stroke stroke) {
        executors.diskIO(() -> insertStrokeSync(stroke));
    }

    /** Delete a single stroke by id (used for undo), off the caller's thread. */
    public void deleteStroke(String strokeId) {
        executors.diskIO(() -> deleteStrokeSync(strokeId));
    }

    /** Delete every stroke on a whiteboard (used by Clear), off the caller's thread. */
    public void deleteAllForWhiteboard(String whiteboardId) {
        executors.diskIO(() -> deleteAllForWhiteboardSync(whiteboardId));
    }

    // ── Blocking: for callers already on the disk thread ──────────────────────

    /** Insert or replace a stroke (REPLACE means re-saving the same id overwrites it). */
    public void insertStrokeSync(Stroke stroke) {
        if (stroke.id == null) stroke.id = UUID.randomUUID().toString();

        ContentValues v = new ContentValues();
        v.put("id", stroke.id);
        v.put("whiteboard_id", stroke.whiteboardId);
        v.put("author_id", stroke.authorId);
        v.put("tool", stroke.tool);
        v.put("color", stroke.color);
        v.put("width", stroke.width);
        v.put("points_blob", serializePoints(stroke.points));
        v.put("created_at", stroke.createdAt);

        db.getWritableDatabase().insertWithOnConflict(
                "strokes", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Fetch all strokes for a whiteboard, ordered by draw order (oldest first). */
    public List<Stroke> getByWhiteboardSync(String whiteboardId) {
        List<Stroke> results = new ArrayList<>();
        Cursor c = db.getReadableDatabase().query(
                "strokes", null,
                "whiteboard_id = ?", new String[]{whiteboardId},
                null, null, "created_at ASC");
        while (c.moveToNext()) {
            results.add(fromCursor(c));
        }
        c.close();
        return results;
    }

    /** Delete a single stroke by id (used for undo). */
    public void deleteStrokeSync(String strokeId) {
        db.getWritableDatabase().delete("strokes", "id = ?", new String[]{strokeId});
    }

    /** Delete all strokes for a whiteboard (used for "Clear" button). */
    public void deleteAllForWhiteboardSync(String whiteboardId) {
        db.getWritableDatabase().delete(
                "strokes", "whiteboard_id = ?", new String[]{whiteboardId});
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Stroke fromCursor(Cursor c) {
        Stroke s = new Stroke();
        s.id           = c.getString(c.getColumnIndexOrThrow("id"));
        s.whiteboardId = c.getString(c.getColumnIndexOrThrow("whiteboard_id"));
        s.authorId     = c.getString(c.getColumnIndexOrThrow("author_id"));
        s.tool         = c.getInt(c.getColumnIndexOrThrow("tool"));
        s.color        = c.getInt(c.getColumnIndexOrThrow("color"));
        s.width        = c.getFloat(c.getColumnIndexOrThrow("width"));
        s.createdAt    = c.getLong(c.getColumnIndexOrThrow("created_at"));
        s.points       = deserializePoints(c.getBlob(c.getColumnIndexOrThrow("points_blob")));
        return s;
    }

    private byte[] serializePoints(List<PointF> points) {
        if (points == null || points.isEmpty()) return new byte[0];
        ByteBuffer buf = ByteBuffer.allocate(points.size() * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (PointF p : points) {
            buf.putFloat(p.x);
            buf.putFloat(p.y);
        }
        return buf.array();
    }

    private List<PointF> deserializePoints(byte[] blob) {
        List<PointF> points = new ArrayList<>();
        if (blob == null || blob.length == 0) return points;
        ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        while (buf.remaining() >= 8) {
            float x = buf.getFloat();
            float y = buf.getFloat();
            points.add(new PointF(x, y));
        }
        return points;
    }
}