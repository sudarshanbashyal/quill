package mse.quill.bundle;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import mse.quill.data.StrokeRepository;
import mse.quill.data.WhiteboardRepository;
import mse.quill.data.WhiteboardTextRepository;
import mse.quill.data.model.Stroke;
import mse.quill.data.model.Tag;
import mse.quill.data.model.Whiteboard;
import mse.quill.data.model.WhiteboardText;
import mse.quill.data.serialization.NoteDocument;
import mse.quill.data.model.AudioSegment;
import mse.quill.data.model.ImageSegment;
import mse.quill.data.model.NoteSegment;

/**
 * Packs one note into a {@link QuillBundle}.
 *
 * <p>Streams straight into the caller's {@code OutputStream} so the same code writes a file in
 * Downloads and, if a transport ever wants one, a payload — nothing is buffered in memory beyond a
 * single media file's copy window.
 *
 * <p>Runs on a disk thread. The segments handed in are read on the main thread by the caller, so
 * what gets packed is what is on screen rather than what was last autosaved — the same rule the
 * PDF and Markdown exports follow.
 */
public final class BundleWriter {

    private static final int COPY_BUFFER = 16 * 1024;

    private BundleWriter() {}

    /**
     * @param segments the note's segments in document order; media among them are copied in whole,
     *                 and any attached whiteboard is embedded losslessly alongside them.
     * @param tags     copied by name and colour, not id — the receiving device matches on name and
     *                 has no use for a tag id minted somewhere else.
     * @param context  used only to read the attached whiteboards' rows (strokes, text, paper) —
     *                 nothing here is written back to the database.
     */
    public static void write(String title, List<NoteSegment> segments, List<Tag> tags,
                             long createdAt, long updatedAt, Context context, OutputStream out)
            throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);

        // Media first, because a file that turns out to be missing has to be left out of the
        // manifest too — and the manifest can only be written once.
        List<JSONObject> mediaEntries = new ArrayList<>();
        for (NoteSegment segment : segments) {
            if (!segment.isMedia()) continue;
            JSONObject entry = writeMedia(segment, zip);
            if (entry != null) mediaEntries.add(entry);
        }

        for (NoteSegment segment : segments) {
            if (segment.type() != NoteSegment.TYPE_WHITEBOARD) continue;
            writeWhiteboard(segment.id, context, zip);
        }

        writeEntry(zip, QuillBundle.ENTRY_DOCUMENT,
                NoteDocument.toMarkdown(segments).getBytes(StandardCharsets.UTF_8));
        writeEntry(zip, QuillBundle.ENTRY_MANIFEST,
                manifest(title, tags, createdAt, updatedAt, mediaEntries)
                        .getBytes(StandardCharsets.UTF_8));

        // finish() rather than close(): the OutputStream belongs to the caller (a MediaStore row,
        // a FileOutputStream) and closing it here would take that decision away from them.
        zip.finish();
    }

    /**
     * Copies one asset in under {@code media/<id>.<ext>}.
     *
     * @return its manifest entry, or null if the file has gone. A media row whose file is missing
     *         is a real state — the note still renders, minus that embed — and it should degrade
     *         the same way here rather than failing the whole export.
     */
    private static JSONObject writeMedia(NoteSegment segment, ZipOutputStream zip)
            throws IOException {
        File source = segment.filePath() == null ? null : new File(segment.filePath());
        if (source == null || !source.isFile()) return null;

        String entryName = QuillBundle.MEDIA_DIR + segment.id + extensionOf(source.getName());
        // A bundle is plaintext by definition — the receiving copy of Quill has no key of ours —
        // so the media goes in readable rather than as ciphertext nothing on the other side could
        // open. Sharing a locked collection's note is refused before this point; what this covers
        // is a note whose media is still encrypted at rest while the collection is open.
        byte[] plaintext = mse.quill.security.MediaFiles.readPlaintext(source.getAbsolutePath());
        if (plaintext == null) return null;

        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(plaintext);
        zip.closeEntry();

        try {
            JSONObject entry = new JSONObject();
            entry.put(QuillBundle.KEY_MEDIA_ID, segment.id);
            entry.put(QuillBundle.KEY_MEDIA_TYPE, segment.type());
            entry.put(QuillBundle.KEY_MEDIA_FILE, entryName);
            if (segment instanceof ImageSegment) {
                entry.put(QuillBundle.KEY_MEDIA_WIDTH, ((ImageSegment) segment).displayWidth);
            } else if (segment instanceof AudioSegment) {
                entry.put(QuillBundle.KEY_MEDIA_DURATION_MS, ((AudioSegment) segment).durationMs);
            }
            return entry;
        } catch (JSONException e) {
            throw new IOException("could not describe media " + segment.id, e);
        }
    }

    /**
     * Embeds one attached whiteboard under {@code whiteboards/<id>.json}, reusing
     * {@link WhiteboardBundleWriter} so this format doesn't grow a second serialization of a board.
     *
     * <p>A whiteboard whose row is gone (deleted from Home, embed left dangling — the state
     * {@code WhiteboardSegmentView} already renders as "This whiteboard was deleted") is simply not
     * written: there's nothing to embed, and the importer leaves the same dangling embed behind
     * rather than inventing a board that never existed on the sender.
     */
    private static void writeWhiteboard(String whiteboardId, Context context, ZipOutputStream zip)
            throws IOException {
        Whiteboard board = new WhiteboardRepository(context).getByIdSync(whiteboardId);
        if (board == null) return;

        List<Stroke> strokes = new StrokeRepository(context).getByWhiteboardSync(whiteboardId);
        List<WhiteboardText> texts = new WhiteboardTextRepository(context).getByWhiteboardSync(whiteboardId);

        ByteArrayOutputStream boardBytes = new ByteArrayOutputStream();
        WhiteboardBundleWriter.write(board.title, board.background, board.createdAt, board.updatedAt,
                strokes, texts, boardBytes);

        writeEntry(zip, QuillBundle.WHITEBOARDS_DIR + whiteboardId + QuillBundle.WHITEBOARD_ENTRY_SUFFIX,
                boardBytes.toByteArray());
    }

    private static String manifest(String title, List<Tag> tags, long createdAt, long updatedAt,
                                   List<JSONObject> mediaEntries) throws IOException {
        try {
            JSONArray tagArray = new JSONArray();
            if (tags != null) {
                for (Tag tag : tags) {
                    if (tag.name == null || tag.name.trim().isEmpty()) continue;
                    JSONObject entry = new JSONObject();
                    entry.put(QuillBundle.KEY_TAG_NAME, tag.name);
                    entry.put(QuillBundle.KEY_TAG_COLOR, tag.color);
                    tagArray.put(entry);
                }
            }

            JSONObject manifest = new JSONObject();
            manifest.put(QuillBundle.KEY_VERSION, QuillBundle.SCHEMA_VERSION);
            manifest.put(QuillBundle.KEY_TITLE, title == null ? "" : title);
            manifest.put(QuillBundle.KEY_CREATED_AT, createdAt);
            manifest.put(QuillBundle.KEY_UPDATED_AT, updatedAt);
            manifest.put(QuillBundle.KEY_TAGS, tagArray);
            manifest.put(QuillBundle.KEY_MEDIA, new JSONArray(mediaEntries));
            return manifest.toString(2);
        } catch (JSONException e) {
            throw new IOException("could not build manifest", e);
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /** The source file's extension including the dot, or empty. Kept so a picture that leaves as a
     *  {@code .png} arrives as one — the decoder sniffs bytes, but a file manager reads names. */
    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot);
    }
}
