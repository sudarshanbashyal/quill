package mse.quill.collab;

import android.graphics.PointF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import mse.quill.data.model.Stroke;
import mse.quill.data.model.WhiteboardText;

/**
 * The whole wire protocol for a live whiteboard session, per requirements.md Epic C: three
 * message kinds only, plus {@code CLEAR} (host-only, destructive to everyone). Strokes and text
 * items are append-only and id'd, so applying any of these on receipt is a dedupe, never a merge.
 *
 * <p>JSON over Nearby's {@code BYTES} payload — a stroke's point list is small enough that this
 * never needs the {@code FILE} payload type chunking mentioned in the plan.
 */
public final class CollabMessage {

    public static final int TYPE_SNAPSHOT = 1;
    public static final int TYPE_STROKE = 2;
    public static final int TYPE_TEXT = 3;
    public static final int TYPE_RETRACT = 4;
    public static final int TYPE_CLEAR = 5;

    public final int type;
    /** SNAPSHOT only: every stroke currently on the host's board. */
    public List<Stroke> strokes;
    /** SNAPSHOT only: every text item currently on the host's board. */
    public List<WhiteboardText> texts;
    /** STROKE only: the one stroke just completed. */
    public Stroke stroke;
    /** TEXT only: the one text item just placed. */
    public WhiteboardText text;
    /** RETRACT only: the id of the stroke or text item to remove. */
    public String retractId;
    public boolean retractIsText;

    private CollabMessage(int type) {
        this.type = type;
    }

    public static CollabMessage snapshot(List<Stroke> strokes, List<WhiteboardText> texts) {
        CollabMessage m = new CollabMessage(TYPE_SNAPSHOT);
        m.strokes = strokes;
        m.texts = texts;
        return m;
    }

    public static CollabMessage stroke(Stroke stroke) {
        CollabMessage m = new CollabMessage(TYPE_STROKE);
        m.stroke = stroke;
        return m;
    }

    public static CollabMessage text(WhiteboardText text) {
        CollabMessage m = new CollabMessage(TYPE_TEXT);
        m.text = text;
        return m;
    }

    public static CollabMessage retract(String id, boolean isText) {
        CollabMessage m = new CollabMessage(TYPE_RETRACT);
        m.retractId = id;
        m.retractIsText = isText;
        return m;
    }

    public static CollabMessage clear() {
        return new CollabMessage(TYPE_CLEAR);
    }

    public byte[] toBytes() {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            switch (type) {
                case TYPE_SNAPSHOT:
                    JSONArray strokeArr = new JSONArray();
                    for (Stroke s : strokes) strokeArr.put(strokeToJson(s));
                    o.put("strokes", strokeArr);
                    JSONArray textArr = new JSONArray();
                    for (WhiteboardText t : texts) textArr.put(textToJson(t));
                    o.put("texts", textArr);
                    break;
                case TYPE_STROKE:
                    o.put("stroke", strokeToJson(stroke));
                    break;
                case TYPE_TEXT:
                    o.put("text", textToJson(text));
                    break;
                case TYPE_RETRACT:
                    o.put("retractId", retractId);
                    o.put("retractIsText", retractIsText);
                    break;
                case TYPE_CLEAR:
                    break;
            }
            return o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static CollabMessage fromBytes(byte[] bytes) throws JSONException {
        JSONObject o = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        int type = o.getInt("type");
        CollabMessage m = new CollabMessage(type);
        switch (type) {
            case TYPE_SNAPSHOT:
                m.strokes = new ArrayList<>();
                JSONArray strokeArr = o.getJSONArray("strokes");
                for (int i = 0; i < strokeArr.length(); i++) {
                    m.strokes.add(strokeFromJson(strokeArr.getJSONObject(i)));
                }
                m.texts = new ArrayList<>();
                JSONArray textArr = o.getJSONArray("texts");
                for (int i = 0; i < textArr.length(); i++) {
                    m.texts.add(textFromJson(textArr.getJSONObject(i)));
                }
                break;
            case TYPE_STROKE:
                m.stroke = strokeFromJson(o.getJSONObject("stroke"));
                break;
            case TYPE_TEXT:
                m.text = textFromJson(o.getJSONObject("text"));
                break;
            case TYPE_RETRACT:
                m.retractId = o.getString("retractId");
                m.retractIsText = o.getBoolean("retractIsText");
                break;
            case TYPE_CLEAR:
                break;
            default:
                throw new JSONException("Unknown CollabMessage type " + type);
        }
        return m;
    }

    private static JSONObject strokeToJson(Stroke s) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", s.id);
        o.put("whiteboardId", s.whiteboardId);
        o.put("authorId", s.authorId);
        o.put("tool", s.tool);
        o.put("color", s.color);
        o.put("width", s.width);
        o.put("createdAt", s.createdAt);
        JSONArray pts = new JSONArray();
        for (PointF p : s.points) {
            pts.put(p.x);
            pts.put(p.y);
        }
        o.put("points", pts);
        return o;
    }

    private static Stroke strokeFromJson(JSONObject o) throws JSONException {
        Stroke s = new Stroke();
        s.id = o.getString("id");
        s.whiteboardId = o.getString("whiteboardId");
        s.authorId = o.getString("authorId");
        s.tool = o.getInt("tool");
        s.color = o.getInt("color");
        s.width = (float) o.getDouble("width");
        s.createdAt = o.getLong("createdAt");
        JSONArray pts = o.getJSONArray("points");
        List<PointF> points = new ArrayList<>();
        for (int i = 0; i + 1 < pts.length(); i += 2) {
            points.add(new PointF((float) pts.getDouble(i), (float) pts.getDouble(i + 1)));
        }
        s.points = points;
        return s;
    }

    private static JSONObject textToJson(WhiteboardText t) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", t.id);
        o.put("whiteboardId", t.whiteboardId);
        o.put("authorId", t.authorId);
        o.put("x", t.x);
        o.put("y", t.y);
        o.put("text", t.text);
        o.put("color", t.color);
        o.put("size", t.size);
        o.put("createdAt", t.createdAt);
        return o;
    }

    private static WhiteboardText textFromJson(JSONObject o) throws JSONException {
        WhiteboardText t = new WhiteboardText();
        t.id = o.getString("id");
        t.whiteboardId = o.getString("whiteboardId");
        t.authorId = o.getString("authorId");
        t.x = (float) o.getDouble("x");
        t.y = (float) o.getDouble("y");
        t.text = o.getString("text");
        t.color = o.getInt("color");
        t.size = (float) o.getDouble("size");
        t.createdAt = o.getLong("createdAt");
        return t;
    }
}
