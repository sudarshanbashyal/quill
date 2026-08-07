package mse.quill.data.model;

/**
 * A block of typed text placed on a whiteboard.
 *
 * <p>Deliberately shaped like {@link Stroke}: an id'd, immutable thing you add to a board and can
 * only undo, never edit in place. Changing the words means deleting this one and typing another.
 * That keeps a board append-only, which is what lets whiteboards be the app's live-collaboration
 * surface — two devices editing the same mutable text box would be a real conflict to resolve,
 * whereas two devices adding items never is.
 */
public class WhiteboardText {
    public String id;
    public String whiteboardId;
    public String authorId;
    /** Top-left of the text, in canvas coordinates — the same space strokes are stored in. */
    public float x;
    public float y;
    public String text;
    public int color;
    /** Text size in pixels, so it needs no conversion to draw on the canvas. */
    public float size;
    public long createdAt;
}
