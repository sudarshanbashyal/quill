package mse.quill.ui.notes.editor.model;

/**
 * One block of a note as the editor sees it.
 *
 * <p>Segments are a view-layer concept: they're what the note's Markdown document parses into,
 * not how it's stored. Ordering comes from the document, so a segment carries no position. Only
 * media segments outlive the parse — as rows in the asset registry, keyed by {@link #id}, holding
 * the metadata a Markdown link has nowhere to put.
 */
public abstract class NoteSegment {
    public String id;

    public static final int TYPE_TEXT  = 0;
    public static final int TYPE_IMAGE = 1;
    public static final int TYPE_AUDIO = 2;
    public static final int TYPE_QA    = 3;
    public static final int TYPE_WHITEBOARD = 4;

    public abstract int type();

    /** True for segments backed by a file on disk, which need a row in the asset registry. */
    public boolean isMedia() {
        return type() == TYPE_IMAGE || type() == TYPE_AUDIO;
    }

    /** The on-disk file this segment references, or null for segments that aren't media. */
    public String filePath() {
        return null;
    }
}
