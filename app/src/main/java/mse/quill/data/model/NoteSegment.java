package mse.quill.data.model;

/**
 * One block of a note: what the note's Markdown document parses into, and the unit the editor,
 * the serializers and the {@code .quill} bundle format all speak in.
 *
 * <p>A segment is <em>not</em> a storage row. A note is one Markdown document; ordering comes from
 * that document, so a segment carries no position. Only media segments outlive the parse — as rows
 * in the asset registry, keyed by {@link #id}, holding the metadata a Markdown link has nowhere to
 * put.
 *
 * <p>These classes lived in {@code ui.notes.editor.model} until 2026-08-26, which had
 * {@code NoteRepository}, {@code MarkdownSerializer} and {@code BundleWriter} all depending on a
 * package named for a screen — the dependency arrow pointing the wrong way, and the file format
 * nominally owned by the editor. They are the domain, so they live with the domain.
 *
 * <p>{@link TextSegment} holds an {@code android.text.Spannable}, so this package is not
 * Android-free and cannot move to {@code :study}. That is expected — {@code data/model} imports
 * Android elsewhere too — and is not a step toward a pure-JVM domain module.
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
