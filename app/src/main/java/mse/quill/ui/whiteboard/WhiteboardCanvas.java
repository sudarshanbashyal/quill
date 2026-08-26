package mse.quill.ui.whiteboard;

import java.util.List;

import mse.quill.data.model.Stroke;
import mse.quill.data.model.WhiteboardText;

/**
 * Everything a live collaboration session is allowed to do to a board.
 *
 * <p>This is the seam between {@link WhiteboardCollabController}, which owns the network side, and
 * {@link WhiteboardFragment}, which owns the canvas and the database. The controller decodes
 * {@code CollabMessage}s and calls these five methods; it holds no repositories and no
 * {@code WhiteboardView}, and the fragment never sees a message type. Before the split both lived
 * in one 1500-line class, which is why a bug in any of view lifecycle, canvas state or network
 * state could present as a symptom in either of the other two.
 *
 * <p>Implementors own persistence. An incoming item still carries the <em>sender's</em>
 * {@code whiteboard_id} — each device opened its own {@code whiteboards} row — so re-tagging it
 * onto this device's board before the insert is part of the contract here, not something the
 * controller does on the way in. Left as the peer's id, the insert fails the
 * {@code strokes → whiteboards} foreign key and takes the process with it.
 */
public interface WhiteboardCanvas {

    /** A stroke someone else drew: show it, save it, re-tag it onto this board first. */
    void applyStroke(Stroke stroke);

    /** A text item someone else committed — same contract as {@link #applyStroke}. */
    void applyText(WhiteboardText text);

    /**
     * Takes back a single item somebody undid on their own device.
     *
     * @param isText which table and which view call to use. It rides along on the wire rather than
     *               being looked up, because by the time a retract arrives the item may already be
     *               gone from both — and "which kind was it" would then have no answer.
     */
    void retract(String id, boolean isText);

    /** Wipes the board, locally and in the database. Only ever the host's CLEAR arriving. */
    void clearAll();

    /**
     * Replaces the whole board with the host's, as a joiner's opening state.
     *
     * <p>Strokes and texts arrive already sorted by creation time: the snapshot travels in chunks
     * that can be reassembled in any order, so draw order is restored from timestamps rather than
     * inherited from the wire. Implementors must make the delete and the re-insert one ordered
     * unit — a half-replaced board is worse than one that appears a moment later.
     */
    void replaceAll(List<Stroke> strokes, List<WhiteboardText> texts);
}
