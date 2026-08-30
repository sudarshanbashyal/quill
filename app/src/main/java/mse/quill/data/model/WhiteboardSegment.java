package mse.quill.data.model;

/**
 * A whiteboard attached to a note.
 *
 * <p>Unlike an image or a recording, this owns nothing: the board is a row in {@code whiteboards}
 * with its own screen and its own life, and the note only points at it. So there is no file, no
 * asset row, and {@link #isMedia()} stays false — {@link #id} <em>is</em> the whiteboard's id,
 * which is what makes the reference survive a save without a side table to keep in step.
 *
 * <p>Removing the embed therefore detaches the board rather than deleting it; the board stays on
 * Home. That also means a board can be attached to more than one note, and that deleting the board
 * elsewhere leaves an embed pointing at nothing — which the segment view is expected to handle.
 */
public class WhiteboardSegment extends NoteSegment {

    public WhiteboardSegment(String whiteboardId) {
        this.id = whiteboardId;
    }

    public String whiteboardId() {
        return id;
    }

    @Override
    public int type() { return TYPE_WHITEBOARD; }
}
