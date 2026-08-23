package mse.quill.collab;

import android.graphics.PointF;

import com.google.android.gms.nearby.connection.ConnectionsClient;

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
 * <p>JSON over Nearby's {@code BYTES} payload. One stroke's point list is comfortably small; a
 * whole board's is not, which is why {@code SNAPSHOT} is the one message kind that arrives in
 * numbered chunks — see {@link #snapshotChunks}.
 */
public final class CollabMessage {

    public static final int TYPE_SNAPSHOT = 1;
    public static final int TYPE_STROKE = 2;
    public static final int TYPE_TEXT = 3;
    public static final int TYPE_RETRACT = 4;
    public static final int TYPE_CLEAR = 5;
    /** Name-exchange handshake, sent right after connecting and relayed by the host so every
     *  peer's display name converges across the whole star topology. */
    public static final int TYPE_PEER_INFO = 6;
    /** Host broadcasts this before tearing the session down explicitly, so joiners can tell
     *  "the host ended it" apart from a bare connection loss. */
    public static final int TYPE_HOST_ENDED = 7;
    /** A joiner sends this to the host before disconnecting explicitly ("Leave"); the host
     *  relays it to the remaining peers instead of dropping the whole session. */
    public static final int TYPE_PEER_LEFT = 8;
    /** Whether a peer is actually looking at the board, as opposed to merely still connected.
     *  Relayed by the host like {@link #TYPE_PEER_INFO}, and for the same reason: a joiner only
     *  learns about the rest of the star through the middle of it. */
    public static final int TYPE_PRESENCE = 9;

    public final int type;
    /** SNAPSHOT only: every stroke currently on the host's board. */
    public List<Stroke> strokes;
    /** SNAPSHOT only: every text item currently on the host's board. */
    public List<WhiteboardText> texts;
    /** SNAPSHOT only: which chunk of the board this is, and how many chunks make up the whole —
     *  see {@link #snapshotChunks}. A single-chunk snapshot is {@code 0} of {@code 1}. */
    public int snapshotIndex;
    public int snapshotCount = 1;
    /** STROKE only: the one stroke just completed. */
    public Stroke stroke;
    /** TEXT only: the one text item just placed. */
    public WhiteboardText text;
    /** RETRACT only: the id of the stroke or text item to remove. */
    public String retractId;
    public boolean retractIsText;
    /** PEER_INFO/PEER_LEFT only: the id of the peer the message describes (the sender's own id
     *  for PEER_INFO, the departing peer's id for PEER_LEFT). */
    public String peerId;
    /** PEER_INFO only: the display name to associate with {@link #peerId}. */
    public String peerDisplayName;
    /** PRESENCE only: whether {@link #peerId} currently has the whiteboard open. */
    public boolean viewing;

    /**
     * How much board goes into one chunk. Three quarters of Nearby's ceiling rather than all of
     * it: the per-chunk cost is measured on each item's own JSON, and the envelope around them
     * (the array brackets, the type field, and UTF-8 expanding any non-ASCII text) is not free.
     * Read the ceiling rather than hard-coding it — it has changed across Play Services versions,
     * and a stale copy of it here would be a size limit nobody remembers writing down.
     */
    private static final int CHUNK_BUDGET_BYTES = ConnectionsClient.MAX_BYTES_DATA_SIZE * 3 / 4;

    private CollabMessage(int type) {
        this.type = type;
    }

    public static CollabMessage snapshot(List<Stroke> strokes, List<WhiteboardText> texts) {
        CollabMessage m = new CollabMessage(TYPE_SNAPSHOT);
        m.strokes = strokes;
        m.texts = texts;
        return m;
    }

    /**
     * The board split into as many messages as it takes to fit through Nearby.
     *
     * <p>A {@code BYTES} payload is capped at {@link ConnectionsClient#MAX_BYTES_DATA_SIZE} (a
     * little under 1 MB) and a larger one fails the send outright — silently, as far as the sender
     * can tell, which is the part that makes it worth defending against rather than hoping about.
     * A snapshot is the one message kind that can get there: it carries the whole board at once,
     * and a stroke costs roughly ten bytes per captured point, so a long-lived board full of ink
     * eventually crosses the line no single-payload version could survive.
     *
     * <p>Always at least one chunk, empty board included — an empty snapshot is still the
     * instruction "the host's board is what you should be showing".
     */
    public static List<CollabMessage> snapshotChunks(List<Stroke> strokes, List<WhiteboardText> texts) {
        List<CollabMessage> chunks = new ArrayList<>();
        List<Stroke> batchStrokes = new ArrayList<>();
        List<WhiteboardText> batchTexts = new ArrayList<>();
        int budget = 0;
        try {
            for (Stroke s : strokes) {
                int cost = jsonSize(strokeToJson(s));
                if (budget + cost > CHUNK_BUDGET_BYTES && !(batchStrokes.isEmpty() && batchTexts.isEmpty())) {
                    chunks.add(snapshot(batchStrokes, batchTexts));
                    batchStrokes = new ArrayList<>();
                    batchTexts = new ArrayList<>();
                    budget = 0;
                }
                batchStrokes.add(s);
                budget += cost;
            }
            for (WhiteboardText t : texts) {
                int cost = jsonSize(textToJson(t));
                if (budget + cost > CHUNK_BUDGET_BYTES && !(batchStrokes.isEmpty() && batchTexts.isEmpty())) {
                    chunks.add(snapshot(batchStrokes, batchTexts));
                    batchStrokes = new ArrayList<>();
                    batchTexts = new ArrayList<>();
                    budget = 0;
                }
                batchTexts.add(t);
                budget += cost;
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        chunks.add(snapshot(batchStrokes, batchTexts));

        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).snapshotIndex = i;
            chunks.get(i).snapshotCount = chunks.size();
        }
        return chunks;
    }

    private static int jsonSize(JSONObject o) {
        return o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
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

    public static CollabMessage peerInfo(String peerId, String displayName) {
        CollabMessage m = new CollabMessage(TYPE_PEER_INFO);
        m.peerId = peerId;
        m.peerDisplayName = displayName;
        return m;
    }

    public static CollabMessage presence(String peerId, boolean viewing) {
        CollabMessage m = new CollabMessage(TYPE_PRESENCE);
        m.peerId = peerId;
        m.viewing = viewing;
        return m;
    }

    public static CollabMessage hostEnded() {
        return new CollabMessage(TYPE_HOST_ENDED);
    }

    public static CollabMessage peerLeft(String peerId) {
        CollabMessage m = new CollabMessage(TYPE_PEER_LEFT);
        m.peerId = peerId;
        return m;
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
                    o.put("chunk", snapshotIndex);
                    o.put("chunks", snapshotCount);
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
                case TYPE_PEER_INFO:
                    o.put("peerId", peerId);
                    o.put("peerDisplayName", peerDisplayName);
                    break;
                case TYPE_HOST_ENDED:
                    break;
                case TYPE_PEER_LEFT:
                    o.put("peerId", peerId);
                    break;
                case TYPE_PRESENCE:
                    o.put("peerId", peerId);
                    o.put("viewing", viewing);
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
                m.snapshotIndex = o.optInt("chunk", 0);
                m.snapshotCount = o.optInt("chunks", 1);
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
            case TYPE_PEER_INFO:
                m.peerId = o.getString("peerId");
                m.peerDisplayName = o.optString("peerDisplayName", null);
                break;
            case TYPE_HOST_ENDED:
                break;
            case TYPE_PEER_LEFT:
                m.peerId = o.getString("peerId");
                break;
            case TYPE_PRESENCE:
                m.peerId = o.getString("peerId");
                m.viewing = o.getBoolean("viewing");
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
