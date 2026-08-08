package mse.quill.share;

/**
 * The {@code .quillpack} container — a whole collection, as a zip of {@link QuillBundle}s.
 *
 * <pre>
 * manifest.json      type marker, name, color, note count
 * notes/0.quill      one entry per note, each itself a complete, valid .quill bundle
 * notes/1.quill
 * ...
 * </pre>
 *
 * <p>Deliberately a zip of zips rather than one flattened archive: each entry under {@code notes/}
 * is exactly what {@link BundleWriter} already produces and {@link BundleReader} already reads, so
 * importing a pack is "make a collection, then run the existing note importer once per entry" —
 * nothing about a single note's format has to know it might arrive as part of a pack.
 *
 * <p>{@link #KEY_NOTE_COUNT} is what lets a reader trying several bundle types in turn — this one,
 * {@link QuillBundle}, {@link WhiteboardBundle} — tell a collection manifest apart from a note's:
 * a {@code .quill} manifest never has it, so its absence is the rejection.
 */
public final class CollectionBundle {

    public static final String EXTENSION = "quillpack";
    public static final String MIME_TYPE = "application/zip";

    public static final int SCHEMA_VERSION = 1;

    public static final String ENTRY_MANIFEST = "manifest.json";
    public static final String NOTES_DIR = "notes/";

    public static final String KEY_VERSION = "version";
    public static final String KEY_NAME = "name";
    public static final String KEY_COLOR = "color";
    public static final String KEY_NOTE_COUNT = "noteCount";

    private CollectionBundle() {}
}
