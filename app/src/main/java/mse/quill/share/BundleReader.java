package mse.quill.share;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import mse.quill.data.model.Stroke;
import mse.quill.data.model.WhiteboardText;

/**
 * Unpacks a {@link QuillBundle} into something {@link mse.quill.data.NoteImporter} can insert.
 *
 * <p>Nothing here touches the database. The split is what makes the format testable on its own and
 * keeps a malformed file from ever reaching a transaction — this class either returns a whole
 * {@link Contents} or throws, and the importer only sees files that parsed.
 *
 * <p><b>The file is untrusted.</b> It arrived from another device over a transport with no notion
 * of who sent it, so every entry name is checked before it becomes a path
 * ({@link #mediaFileName}), the archive is read as a stream rather than seeked, and unrecognised
 * entries are ignored rather than unpacked. A future version of the format may add entries this
 * reader has never heard of; skipping them is also what lets {@link QuillBundle#SCHEMA_VERSION}
 * only have to move for changes that genuinely break understanding.
 */
public final class BundleReader {

    private static final String TAG = "BundleReader";
    private static final int COPY_BUFFER = 16 * 1024;
    /** A note is text and a handful of photos. Well above anything Quill produces, low enough that
     *  a hostile or corrupt archive can't fill the cache partition before anyone notices. */
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;

    /** A bundle that parsed. Media are already on disk, in {@code cacheDir}, still owned by the
     *  caller — {@link #discard()} is how they go away if the import doesn't finish. */
    public static final class Contents {
        public final String title;
        public final String markdown;
        public final long createdAt;
        public final long updatedAt;
        public final List<TagEntry> tags;
        public final List<MediaEntry> media;
        public final List<WhiteboardEntry> whiteboards;
        private final File workDir;

        Contents(String title, String markdown, long createdAt, long updatedAt,
                 List<TagEntry> tags, List<MediaEntry> media, List<WhiteboardEntry> whiteboards,
                 File workDir) {
            this.title = title;
            this.markdown = markdown;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.tags = tags;
            this.media = media;
            this.whiteboards = whiteboards;
            this.workDir = workDir;
        }

        /** Deletes the unpacked media. Safe to call whether or not the import went ahead: the
         *  importer *moves* the files it keeps, so what is left here is only ever leftovers. */
        public void discard() {
            deleteDirectory(workDir);
        }
    }

    public static final class TagEntry {
        public final String name;
        public final int color;

        TagEntry(String name, int color) {
            this.name = name;
            this.color = color;
        }
    }

    public static final class MediaEntry {
        /** The sender's asset id. Only used to find this asset's {@code quill://} reference in the
         *  document so the importer can rewrite it — never stored. */
        public final String sourceId;
        public final int type;
        public final File file;
        public final int width;
        public final int durationMs;

        MediaEntry(String sourceId, int type, File file, int width, int durationMs) {
            this.sourceId = sourceId;
            this.type = type;
            this.file = file;
            this.width = width;
            this.durationMs = durationMs;
        }
    }

    /** One embedded whiteboard, parsed with the same reader a standalone {@code .quillboard} uses. */
    public static final class WhiteboardEntry {
        /** The sender's whiteboard id — what a {@code quill://whiteboard/} embed line in the
         *  document points at, matched the same way a media asset id is. */
        public final String sourceId;
        public final String title;
        public final int background;
        public final long createdAt;
        public final long updatedAt;
        public final List<Stroke> strokes;
        public final List<WhiteboardText> texts;

        WhiteboardEntry(String sourceId, WhiteboardBundleReader.Contents board) {
            this.sourceId = sourceId;
            this.title = board.title;
            this.background = board.background;
            this.createdAt = board.createdAt;
            this.updatedAt = board.updatedAt;
            this.strokes = board.strokes;
            this.texts = board.texts;
        }
    }

    /** A bundle that can't be read. Its message is not shown to the user — the UI says "this isn't
     *  a Quill note" either way — but it is what distinguishes the causes in a log. */
    public static class InvalidBundleException extends IOException {
        InvalidBundleException(String message) {
            super(message);
        }
    }

    private BundleReader() {}

    /**
     * Reads a bundle out of {@code in}, unpacking its media into a fresh directory under
     * {@code cacheDir}.
     *
     * <p>Cache rather than files: until the transaction commits these are nobody's assets, and if
     * the process dies between here and there the system is entitled to clean them up. The
     * importer moves the ones it keeps into private storage, which on the same filesystem is a
     * rename.
     *
     * @throws InvalidBundleException if this isn't a Quill bundle, or is a newer one.
     */
    public static Contents read(InputStream in, File cacheDir) throws IOException {
        File workDir = new File(new File(cacheDir, "import"), UUID.randomUUID().toString());
        if (!workDir.mkdirs()) throw new IOException("could not create " + workDir);

        try {
            return readInto(in, workDir);
        } catch (IOException | RuntimeException e) {
            // Nothing partially unpacked survives a failed read — the caller has no handle to
            // clean up with, since it never got a Contents back.
            deleteDirectory(workDir);
            throw e;
        }
    }

    /** One level deep is enough: {@link #mediaFileName} guarantees nothing nested is ever created
     *  in there, which is the same guard that keeps an archive from writing outside it. */
    private static void deleteDirectory(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                //noinspection ResultOfMethodCallIgnored
                child.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    private static Contents readInto(InputStream in, File workDir) throws IOException {
        byte[] manifestBytes = null;
        String markdown = null;
        // Media can precede the manifest in the archive (BundleWriter puts it there, so that a
        // missing file can be left out of both), so the files are unpacked first and matched to
        // their metadata afterwards.
        Map<String, File> filesByEntryName = new HashMap<>();
        List<WhiteboardEntry> whiteboards = new ArrayList<>();
        long totalBytes = 0;

        ZipInputStream zip = new ZipInputStream(in);
        for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
            String name = entry.getName();

            if (QuillBundle.ENTRY_MANIFEST.equals(name)) {
                byte[] bytes = readAll(zip, MAX_TOTAL_BYTES - totalBytes);
                totalBytes += bytes.length;
                manifestBytes = bytes;
            } else if (QuillBundle.ENTRY_DOCUMENT.equals(name)) {
                byte[] bytes = readAll(zip, MAX_TOTAL_BYTES - totalBytes);
                totalBytes += bytes.length;
                markdown = new String(bytes, StandardCharsets.UTF_8);
            } else {
                String whiteboardId = whiteboardEntryId(name);
                if (whiteboardId != null) {
                    byte[] bytes = readAll(zip, MAX_TOTAL_BYTES - totalBytes);
                    totalBytes += bytes.length;
                    try {
                        whiteboards.add(new WhiteboardEntry(whiteboardId,
                                WhiteboardBundleReader.read(new ByteArrayInputStream(bytes))));
                    } catch (WhiteboardBundleReader.InvalidBundleException e) {
                        // A dangling or corrupt embed degrades the same way a missing media file
                        // does: dropped here, left unresolved in the document.
                        Log.w(TAG, "embedded whiteboard " + whiteboardId + " did not parse", e);
                    }
                    continue;
                }

                String fileName = mediaFileName(name);
                if (fileName == null) continue;   // not ours, or not a name we'll make a path from
                File target = new File(workDir, fileName);
                totalBytes += copyTo(zip, target, MAX_TOTAL_BYTES - totalBytes);
                filesByEntryName.put(name, target);
            }
        }

        if (manifestBytes == null || markdown == null) {
            throw new InvalidBundleException("missing manifest or document");
        }
        return fromManifest(manifestBytes, markdown, filesByEntryName, whiteboards, workDir);
    }

    /** The whiteboard id named by a {@code whiteboards/<id>.json} entry, or null. Whitelisted the
     *  same way {@link #mediaFileName} is: the entry must sit directly in the directory. */
    private static String whiteboardEntryId(String entryName) {
        if (!entryName.startsWith(QuillBundle.WHITEBOARDS_DIR)
                || !entryName.endsWith(QuillBundle.WHITEBOARD_ENTRY_SUFFIX)) {
            return null;
        }
        String id = entryName.substring(QuillBundle.WHITEBOARDS_DIR.length(),
                entryName.length() - QuillBundle.WHITEBOARD_ENTRY_SUFFIX.length());
        if (id.isEmpty() || id.contains("/") || id.contains("\\")) return null;
        return id;
    }

    private static Contents fromManifest(byte[] manifestBytes, String markdown,
                                         Map<String, File> filesByEntryName,
                                         List<WhiteboardEntry> whiteboards, File workDir)
            throws IOException {
        JSONObject manifest;
        try {
            manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new InvalidBundleException("manifest is not JSON");
        }

        int version = manifest.optInt(QuillBundle.KEY_VERSION, 0);
        if (version <= 0 || version > QuillBundle.SCHEMA_VERSION) {
            // Refusing a newer bundle outright, rather than importing whatever parts this build
            // happens to recognise: a note that arrives silently missing half of itself is worse
            // than one that arrives with an explanation.
            throw new InvalidBundleException("unsupported bundle version " + version);
        }

        List<TagEntry> tags = new ArrayList<>();
        JSONArray tagArray = manifest.optJSONArray(QuillBundle.KEY_TAGS);
        for (int i = 0; tagArray != null && i < tagArray.length(); i++) {
            JSONObject entry = tagArray.optJSONObject(i);
            if (entry == null) continue;
            String name = entry.optString(QuillBundle.KEY_TAG_NAME, "").trim();
            if (name.isEmpty()) continue;
            tags.add(new TagEntry(name, entry.optInt(QuillBundle.KEY_TAG_COLOR)));
        }

        List<MediaEntry> media = new ArrayList<>();
        JSONArray mediaArray = manifest.optJSONArray(QuillBundle.KEY_MEDIA);
        for (int i = 0; mediaArray != null && i < mediaArray.length(); i++) {
            JSONObject entry = mediaArray.optJSONObject(i);
            if (entry == null) continue;
            String sourceId = entry.optString(QuillBundle.KEY_MEDIA_ID, "");
            File file = filesByEntryName.get(entry.optString(QuillBundle.KEY_MEDIA_FILE, ""));
            // A manifest entry with no file behind it describes an asset that isn't in the archive.
            // Dropping it leaves its embed unresolved, which the document parser already handles by
            // omitting the embed — the same outcome as a media row whose file has gone.
            if (sourceId.isEmpty() || file == null) continue;
            media.add(new MediaEntry(
                    sourceId,
                    entry.optInt(QuillBundle.KEY_MEDIA_TYPE),
                    file,
                    entry.optInt(QuillBundle.KEY_MEDIA_WIDTH),
                    entry.optInt(QuillBundle.KEY_MEDIA_DURATION_MS)));
        }

        return new Contents(
                manifest.optString(QuillBundle.KEY_TITLE, ""),
                markdown,
                manifest.optLong(QuillBundle.KEY_CREATED_AT),
                manifest.optLong(QuillBundle.KEY_UPDATED_AT),
                tags, media, whiteboards, workDir);
    }

    /**
     * The flat file name a {@code media/} entry may be unpacked as, or null to skip the entry.
     *
     * <p>This is the zip-slip guard, and it is deliberately a whitelist rather than a check for
     * {@code ".."}: the entry has to live directly in {@code media/} and its remaining name has to
     * be a plain file name. An archive from anywhere else can name an entry
     * {@code media/../../databases/quill.db}, and on a path built by concatenation that resolves
     * outside the work directory.
     */
    private static String mediaFileName(String entryName) {
        if (!entryName.startsWith(QuillBundle.MEDIA_DIR)) return null;
        String fileName = entryName.substring(QuillBundle.MEDIA_DIR.length());
        if (fileName.isEmpty() || fileName.contains("/") || fileName.contains("\\")) return null;
        if (fileName.equals(".") || fileName.equals("..")) return null;
        return fileName;
    }

    private static byte[] readAll(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out, limit);
        return out.toByteArray();
    }

    private static long copyTo(InputStream in, File target, long limit) throws IOException {
        try (OutputStream out = new FileOutputStream(target)) {
            return copy(in, out, limit);
        }
    }

    /**
     * Copies at most {@code limit} bytes, then gives up.
     *
     * <p>The limit is enforced as the bytes go past rather than checked against a size the archive
     * declares: a zip entry's stated length is written by whoever built the file, so a bomb
     * declaring 4 KB and inflating to 4 GB would sail through a check made up front. Counting what
     * actually arrives is the only number that can't be lied about.
     */
    private static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER];
        long total = 0;
        for (int read; (read = in.read(buffer)) != -1; ) {
            total += read;
            if (total > limit) {
                throw new InvalidBundleException("bundle exceeds " + MAX_TOTAL_BYTES + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return total;
    }
}
