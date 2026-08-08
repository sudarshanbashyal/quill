package mse.quill.share;

/**
 * The {@code .quillboard} container — one whiteboard, losslessly, as JSON.
 *
 * <p>Unlike {@link QuillBundle}, this is not a zip. A board carries no files — strokes are point
 * lists and text items are strings, both already the kind of thing JSON stores directly — so the
 * container that would hold them has nothing in it. One JSON document is the format; wrapping it in
 * a zip would only be a container for a container.
 *
 * <pre>
 * {
 *   "type": "quillboard", "version": 1,
 *   "title": ..., "background": ..., "createdAt": ..., "updatedAt": ...,
 *   "strokes": [ { "tool", "color", "width", "points": [x, y, x, y, ...], "createdAt" }, ... ],
 *   "texts":   [ { "x", "y", "text", "color", "size", "createdAt" }, ... ]
 * }
 * </pre>
 *
 * <p>{@code authorId} on {@link mse.quill.data.model.Stroke}/{@link mse.quill.data.model.WhiteboardText}
 * is not carried — it is a live-collaboration field with no meaning for a single-device board arriving
 * by file, and {@code data/WhiteboardImporter} mints fresh row ids for everything regardless, the same
 * rule {@code data/NoteImporter} follows for a note.
 */
public final class WhiteboardBundle {

    public static final String EXTENSION = "quillboard";
    /** JSON, not zip — see the class doc. Plain text so a mail client or Quick Share carries it
     *  like any other attachment. */
    public static final String MIME_TYPE = "application/json";

    /** The value {@link #KEY_TYPE} must hold, and what tells an importer trying several formats
     *  in turn that this file is a board rather than a note or a collection. */
    public static final String TYPE = "quillboard";

    public static final int SCHEMA_VERSION = 1;

    public static final String KEY_TYPE = "type";
    public static final String KEY_VERSION = "version";
    public static final String KEY_TITLE = "title";
    public static final String KEY_BACKGROUND = "background";
    public static final String KEY_CREATED_AT = "createdAt";
    public static final String KEY_UPDATED_AT = "updatedAt";
    public static final String KEY_STROKES = "strokes";
    public static final String KEY_TEXTS = "texts";

    public static final String KEY_STROKE_TOOL = "tool";
    public static final String KEY_STROKE_COLOR = "color";
    public static final String KEY_STROKE_WIDTH = "width";
    public static final String KEY_STROKE_POINTS = "points";
    public static final String KEY_STROKE_CREATED_AT = "createdAt";

    public static final String KEY_TEXT_X = "x";
    public static final String KEY_TEXT_Y = "y";
    public static final String KEY_TEXT_VALUE = "text";
    public static final String KEY_TEXT_COLOR = "color";
    public static final String KEY_TEXT_SIZE = "size";
    public static final String KEY_TEXT_CREATED_AT = "createdAt";

    private WhiteboardBundle() {}
}
