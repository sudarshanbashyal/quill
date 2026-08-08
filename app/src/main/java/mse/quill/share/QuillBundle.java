package mse.quill.share;

/**
 * The {@code .quill} container — one note, losslessly, as a zip another copy of Quill can rebuild.
 *
 * <pre>
 * manifest.json      title, timestamps, tags, and one entry per media asset
 * note.md            the note's own Markdown document, quill:// embeds intact
 * media/&lt;id&gt;.&lt;ext&gt;    every image and recording the document references
 * </pre>
 *
 * <p>Deliberately not the Markdown export ({@link mse.quill.util.MarkdownExporter}), which is lossy
 * by design: it flattens images and audio to italic placeholders and Q&amp;A fences to bold
 * paragraphs, because its job is being readable in someone else's editor. That is the right answer
 * when the destination is another tool and the wrong one when the destination is another Quill —
 * a shared note arriving with its pictures replaced by the words "Embedded image" reads as broken.
 * So {@code note.md} here is exactly what sits in {@code notes.content_blob}, and the manifest
 * carries the two things that live beside the document rather than in it: the note's title, which
 * is a column on its row, and the per-asset metadata Markdown link syntax has nowhere to put.
 *
 * <p><b>Ids inside a bundle are the sender's.</b> Nothing on the receiving side reuses them — see
 * {@link mse.quill.data.NoteImporter}, which mints its own and rewrites the document to match.
 * They are written out anyway because the document references assets by id, so the manifest and
 * {@code note.md} have to agree with each other; that agreement is internal to the file, not a
 * claim on the importer.
 */
public final class QuillBundle {

    public static final String EXTENSION = "quill";
    /** A {@code .quill} is a zip, and saying so is what lets a mail client or Quick Share carry it.
     *  Claiming a private type would leave transports guessing, and several refuse to send a
     *  {@code application/octet-stream} attachment at all. */
    public static final String MIME_TYPE = "application/zip";

    /** Bumped when a change would stop an older reader from making sense of the file. Readers
     *  reject anything above their own, which is worth doing now while there is exactly one
     *  version — the alternative is a future format silently importing as a half-empty note. */
    public static final int SCHEMA_VERSION = 1;

    public static final String ENTRY_MANIFEST = "manifest.json";
    public static final String ENTRY_DOCUMENT = "note.md";
    public static final String MEDIA_DIR = "media/";

    // Manifest keys.
    static final String KEY_VERSION = "version";
    static final String KEY_TITLE = "title";
    static final String KEY_CREATED_AT = "createdAt";
    static final String KEY_UPDATED_AT = "updatedAt";
    static final String KEY_TAGS = "tags";
    static final String KEY_TAG_NAME = "name";
    static final String KEY_TAG_COLOR = "color";
    static final String KEY_MEDIA = "media";
    static final String KEY_MEDIA_ID = "id";
    static final String KEY_MEDIA_TYPE = "type";
    static final String KEY_MEDIA_FILE = "file";
    static final String KEY_MEDIA_WIDTH = "width";
    static final String KEY_MEDIA_DURATION_MS = "durationMs";

    private QuillBundle() {}
}
