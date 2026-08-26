package mse.quill.bundle;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacks a {@link CollectionBundle} into what {@code data/CollectionImporter} needs: the
 * collection's own fields, plus each member note's raw {@code .quill} bytes for
 * {@link BundleReader} to read in turn.
 *
 * <p>Untrusted input, same posture as {@link BundleReader}: entries are whitelisted by name
 * (zip-slip guard) and counted as bytes arrive against a cap rather than trusting a declared size.
 * The cap is larger than a single note's — a pack is several notes' worth of media — but still
 * finite.
 */
public final class CollectionBundleReader {

    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    public static final class Contents {
        public final String name;
        public final int color;
        /** Each entry's raw bytes, in the order they were written — {@code notes/0.quill},
         *  {@code notes/1.quill}, and so on — ready for {@link BundleReader#read} to parse. */
        public final List<byte[]> noteBundles;

        Contents(String name, int color, List<byte[]> noteBundles) {
            this.name = name;
            this.color = color;
            this.noteBundles = noteBundles;
        }
    }

    public static class InvalidBundleException extends IOException {
        InvalidBundleException(String message) {
            super(message);
        }
    }

    private CollectionBundleReader() {}

    public static Contents read(InputStream in) throws IOException {
        byte[] manifestBytes = null;
        // Keyed by the entry's numeric prefix so 10.quill sorts after 2.quill, not before it.
        TreeMap<Integer, byte[]> notesByIndex = new TreeMap<>();
        long totalBytes = 0;

        ZipInputStream zip = new ZipInputStream(in);
        for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
            String name = entry.getName();
            if (CollectionBundle.ENTRY_MANIFEST.equals(name)) {
                byte[] bytes = readAll(zip, MAX_TOTAL_BYTES - totalBytes);
                totalBytes += bytes.length;
                manifestBytes = bytes;
            } else {
                Integer index = noteEntryIndex(name);
                if (index == null) continue;   // not ours, or not a name we trust
                byte[] bytes = readAll(zip, MAX_TOTAL_BYTES - totalBytes);
                totalBytes += bytes.length;
                notesByIndex.put(index, bytes);
            }
        }

        if (manifestBytes == null) throw new InvalidBundleException("missing manifest");

        JSONObject manifest;
        try {
            manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new InvalidBundleException("manifest is not JSON");
        }

        // A .quill manifest never carries a note count — this is what tells the two formats apart
        // when an importer is trying each in turn against an unknown file.
        if (!manifest.has(CollectionBundle.KEY_NOTE_COUNT)) {
            throw new InvalidBundleException("not a Quill collection");
        }
        int version = manifest.optInt(CollectionBundle.KEY_VERSION, 0);
        if (version <= 0 || version > CollectionBundle.SCHEMA_VERSION) {
            throw new InvalidBundleException("unsupported collection bundle version " + version);
        }

        return new Contents(
                manifest.optString(CollectionBundle.KEY_NAME, ""),
                manifest.optInt(CollectionBundle.KEY_COLOR),
                new ArrayList<>(notesByIndex.values()));
    }

    /**
     * The numeric index of a {@code notes/<n>.quill} entry, or null to skip it.
     *
     * <p>A whitelist, the same shape as {@link BundleReader}'s {@code mediaFileName}: the entry has
     * to live directly in {@code notes/} and its name has to be plain digits plus the expected
     * extension, or a hostile archive naming an entry {@code notes/../../databases/quill.db} would
     * resolve outside where anything here expects it to land — even though, unlike media, these
     * bytes never touch disk before {@link BundleReader} parses them.
     */
    private static Integer noteEntryIndex(String entryName) {
        if (!entryName.startsWith(CollectionBundle.NOTES_DIR)) return null;
        String rest = entryName.substring(CollectionBundle.NOTES_DIR.length());
        String suffix = "." + QuillBundle.EXTENSION;
        if (!rest.endsWith(suffix) || rest.contains("/") || rest.contains("\\")) return null;
        String digits = rest.substring(0, rest.length() - suffix.length());
        if (digits.isEmpty()) return null;
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) return null;
        }
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        for (int read; (read = in.read(buffer)) != -1; ) {
            total += read;
            if (total > limit) {
                throw new InvalidBundleException("bundle exceeds " + MAX_TOTAL_BYTES + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
