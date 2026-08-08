package mse.quill.share;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import mse.quill.data.model.Stroke;
import mse.quill.data.model.WhiteboardText;

/**
 * Packs one whiteboard into a {@link WhiteboardBundle}.
 *
 * <p>Same shape as {@link BundleWriter} — streams into the caller's {@code OutputStream}, runs on a
 * disk thread — but there is no media pass, since a board has none.
 */
public final class WhiteboardBundleWriter {

    private WhiteboardBundleWriter() {}

    public static void write(String title, int background, long createdAt, long updatedAt,
                             List<Stroke> strokes, List<WhiteboardText> texts, OutputStream out)
            throws IOException {
        try {
            JSONObject root = new JSONObject();
            root.put(WhiteboardBundle.KEY_TYPE, WhiteboardBundle.TYPE);
            root.put(WhiteboardBundle.KEY_VERSION, WhiteboardBundle.SCHEMA_VERSION);
            root.put(WhiteboardBundle.KEY_TITLE, title == null ? "" : title);
            root.put(WhiteboardBundle.KEY_BACKGROUND, background);
            root.put(WhiteboardBundle.KEY_CREATED_AT, createdAt);
            root.put(WhiteboardBundle.KEY_UPDATED_AT, updatedAt);
            root.put(WhiteboardBundle.KEY_STROKES, strokesJson(strokes));
            root.put(WhiteboardBundle.KEY_TEXTS, textsJson(texts));
            out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("could not build whiteboard bundle", e);
        }
    }

    private static JSONArray strokesJson(List<Stroke> strokes) throws JSONException {
        JSONArray array = new JSONArray();
        for (Stroke stroke : strokes) {
            JSONObject entry = new JSONObject();
            entry.put(WhiteboardBundle.KEY_STROKE_TOOL, stroke.tool);
            entry.put(WhiteboardBundle.KEY_STROKE_COLOR, stroke.color);
            entry.put(WhiteboardBundle.KEY_STROKE_WIDTH, stroke.width);
            entry.put(WhiteboardBundle.KEY_STROKE_CREATED_AT, stroke.createdAt);
            JSONArray points = new JSONArray();
            if (stroke.points != null) {
                for (android.graphics.PointF p : stroke.points) {
                    points.put(p.x);
                    points.put(p.y);
                }
            }
            entry.put(WhiteboardBundle.KEY_STROKE_POINTS, points);
            array.put(entry);
        }
        return array;
    }

    private static JSONArray textsJson(List<WhiteboardText> texts) throws JSONException {
        JSONArray array = new JSONArray();
        for (WhiteboardText text : texts) {
            JSONObject entry = new JSONObject();
            entry.put(WhiteboardBundle.KEY_TEXT_X, text.x);
            entry.put(WhiteboardBundle.KEY_TEXT_Y, text.y);
            entry.put(WhiteboardBundle.KEY_TEXT_VALUE, text.text == null ? "" : text.text);
            entry.put(WhiteboardBundle.KEY_TEXT_COLOR, text.color);
            entry.put(WhiteboardBundle.KEY_TEXT_SIZE, text.size);
            entry.put(WhiteboardBundle.KEY_TEXT_CREATED_AT, text.createdAt);
            array.put(entry);
        }
        return array;
    }
}
