package mse.quill.bundle;

import android.graphics.PointF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import mse.quill.data.model.Stroke;
import mse.quill.data.model.WhiteboardText;

/**
 * Unpacks a {@link WhiteboardBundle} into something {@code data/WhiteboardImporter} can insert.
 *
 * <p>Same untrusted-input posture as {@link BundleReader}: read as a stream with a byte cap rather
 * than trusting a declared length, and a file that doesn't parse or doesn't claim
 * {@link WhiteboardBundle#TYPE} is rejected rather than guessed at — that second check is what lets
 * an importer try this reader against a {@code .quill} note or a {@code .quillpack} collection
 * (both valid JSON-free zips, so they fail here on the type-sniff, not a parse error) without
 * misreading one as an empty board.
 */
public final class WhiteboardBundleReader {

    /** A board's content is small text — points as JSON numbers, no media — so this is generous
     *  headroom against a hostile file rather than a realistic size. */
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    public static final class Contents {
        public final String title;
        public final int background;
        public final long createdAt;
        public final long updatedAt;
        public final List<Stroke> strokes;
        public final List<WhiteboardText> texts;

        Contents(String title, int background, long createdAt, long updatedAt,
                 List<Stroke> strokes, List<WhiteboardText> texts) {
            this.title = title;
            this.background = background;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.strokes = strokes;
            this.texts = texts;
        }
    }

    public static class InvalidBundleException extends IOException {
        InvalidBundleException(String message) {
            super(message);
        }
    }

    private WhiteboardBundleReader() {}

    public static Contents read(InputStream in) throws IOException {
        String json = readAll(in);
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            throw new InvalidBundleException("not JSON");
        }

        if (!WhiteboardBundle.TYPE.equals(root.optString(WhiteboardBundle.KEY_TYPE))) {
            throw new InvalidBundleException("not a Quill whiteboard");
        }
        int version = root.optInt(WhiteboardBundle.KEY_VERSION, 0);
        if (version <= 0 || version > WhiteboardBundle.SCHEMA_VERSION) {
            throw new InvalidBundleException("unsupported whiteboard bundle version " + version);
        }

        List<Stroke> strokes = new ArrayList<>();
        JSONArray strokeArray = root.optJSONArray(WhiteboardBundle.KEY_STROKES);
        for (int i = 0; strokeArray != null && i < strokeArray.length(); i++) {
            JSONObject entry = strokeArray.optJSONObject(i);
            if (entry == null) continue;
            Stroke stroke = new Stroke();
            stroke.tool = entry.optInt(WhiteboardBundle.KEY_STROKE_TOOL);
            stroke.color = entry.optInt(WhiteboardBundle.KEY_STROKE_COLOR);
            stroke.width = (float) entry.optDouble(WhiteboardBundle.KEY_STROKE_WIDTH, 1);
            stroke.createdAt = entry.optLong(WhiteboardBundle.KEY_STROKE_CREATED_AT);
            stroke.points = readPoints(entry.optJSONArray(WhiteboardBundle.KEY_STROKE_POINTS));
            strokes.add(stroke);
        }

        List<WhiteboardText> texts = new ArrayList<>();
        JSONArray textArray = root.optJSONArray(WhiteboardBundle.KEY_TEXTS);
        for (int i = 0; textArray != null && i < textArray.length(); i++) {
            JSONObject entry = textArray.optJSONObject(i);
            if (entry == null) continue;
            WhiteboardText text = new WhiteboardText();
            text.x = (float) entry.optDouble(WhiteboardBundle.KEY_TEXT_X, 0);
            text.y = (float) entry.optDouble(WhiteboardBundle.KEY_TEXT_Y, 0);
            text.text = entry.optString(WhiteboardBundle.KEY_TEXT_VALUE, "");
            text.color = entry.optInt(WhiteboardBundle.KEY_TEXT_COLOR);
            text.size = (float) entry.optDouble(WhiteboardBundle.KEY_TEXT_SIZE, 32);
            text.createdAt = entry.optLong(WhiteboardBundle.KEY_TEXT_CREATED_AT);
            texts.add(text);
        }

        return new Contents(
                root.optString(WhiteboardBundle.KEY_TITLE, ""),
                root.optInt(WhiteboardBundle.KEY_BACKGROUND, 0),
                root.optLong(WhiteboardBundle.KEY_CREATED_AT),
                root.optLong(WhiteboardBundle.KEY_UPDATED_AT),
                strokes, texts);
    }

    private static List<PointF> readPoints(JSONArray flat) {
        List<PointF> points = new ArrayList<>();
        if (flat == null) return points;
        for (int i = 0; i + 1 < flat.length(); i += 2) {
            points.add(new PointF((float) flat.optDouble(i, 0), (float) flat.optDouble(i + 1, 0)));
        }
        return points;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        for (int read; (read = in.read(buffer)) != -1; ) {
            total += read;
            if (total > MAX_BYTES) throw new InvalidBundleException("bundle exceeds " + MAX_BYTES + " bytes");
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
