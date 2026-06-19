package mse.quill.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.PointF;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import mse.quill.model.Stroke;

public class StrokeDao {
    private final AppDatabase db;

    public StrokeDao(AppDatabase db) {
        this.db = db;
    }

    public void insertStroke(Stroke stroke) {
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

    public List<Stroke> getByWhiteboard(String whiteboardId) {
        List<Stroke> results = new ArrayList<>();
        Cursor c = db.getReadableDatabase().query(
                "strokes", null,
                "whiteboard_id = ?",
                new String[]{whiteboardId},
                null, null, "created_at ASC");
        while (c.moveToNext()) results.add(fromCursor(c));
        c.close();
        return results;
    }

    private byte[] serializePoints(List<PointF> points) {
        // convert list of PointF → byte[]
        // e.g. ByteBuffer: 8 bytes per point (float x, float y)
        ByteBuffer buf = ByteBuffer.allocate(points.size() * 8);
        for (PointF p : points) { buf.putFloat(p.x); buf.putFloat(p.y); }
        return buf.array();
    }
}
