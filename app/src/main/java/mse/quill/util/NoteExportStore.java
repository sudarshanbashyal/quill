package mse.quill.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Writes an exported note into the device's shared Downloads collection, under a {@code Quill}
 * folder, so it is somewhere the user can actually find it and hand to another app.
 *
 * <p>Downloads rather than Documents because every file manager surfaces it and the system's own
 * "Downloads" app lists it; a note leaving Quill is on its way somewhere else, not being filed.
 *
 * <p>Sibling of {@link ImageExporter}, which does the same job for a note's images into Pictures.
 * They are kept apart rather than generalised: the collections, the naming and the metadata differ,
 * and the shared part — insert, stream, publish — is four lines.
 */
public final class NoteExportStore {

    private static final String FOLDER = "Quill";
    /** Longest slice of a note's title used in a filename, before the timestamp. Long enough to
     *  recognise, short enough that no filesystem in play objects to the result. */
    private static final int MAX_TITLE_CHARS = 60;

    /** Writes the export's bytes. Runs on whatever thread {@link #save} was called from — which
     *  must not be the main one. */
    public interface Writer {
        void writeTo(OutputStream out) throws IOException;
    }

    /** Where an export ended up: what to call it, and something a viewer app can open. */
    public static final class Saved {
        public final String displayName;
        public final Uri uri;

        Saved(String displayName, Uri uri) {
            this.displayName = displayName;
            this.uri = uri;
        }
    }

    private NoteExportStore() {}

    /**
     * Whether saving needs {@code WRITE_EXTERNAL_STORAGE} first — same rule as
     * {@link ImageExporter#requiresStoragePermission()}: scoped storage (API 29+) lets an app add
     * its own entries to a shared collection unprompted, and below that it is a permissioned write.
     */
    public static boolean requiresStoragePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
    }

    /**
     * Saves one exported note.
     *
     * @return where it landed, or null if nothing was written. The name is what the confirmation
     *         shows and the uri is what its "Open" button hands to a viewer, so both are worth
     *         having back rather than re-deriving.
     */
    public static Saved save(Context context, String noteTitle, String extension,
                             String mimeType, Writer writer) {
        String displayName = fileName(noteTitle, extension);
        Uri uri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? saveViaMediaStore(context, displayName, mimeType, writer)
                : saveToPublicDirectory(context, displayName, writer);
        return uri == null ? null : new Saved(displayName, uri);
    }

    private static Uri saveViaMediaStore(Context context, String displayName, String mimeType,
                                         Writer writer) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + File.separator + FOLDER);
        // Hidden from other apps until the bytes are there, so nothing lists a half-written export.
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        Uri target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (target == null) return null;

        try (OutputStream out = resolver.openOutputStream(target)) {
            if (out == null) throw new IOException("no output stream for " + target);
            writer.writeTo(out);
        } catch (IOException | SecurityException e) {
            // Otherwise the row survives as a permanently empty download.
            resolver.delete(target, null, null);
            return null;
        }

        ContentValues published = new ContentValues();
        published.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(target, published, null, null);
        return target;
    }

    /** Pre-scoped-storage path: a plain file, which is why this one needs the permission. The uri
     *  has to come back through the FileProvider — a {@code file://} uri is not something another
     *  app is allowed to receive. */
    private static Uri saveToPublicDirectory(Context context, String displayName, Writer writer) {
        File folder = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FOLDER);
        if (!folder.exists() && !folder.mkdirs()) return null;

        File target = new File(folder, displayName);
        try (OutputStream out = new FileOutputStream(target)) {
            writer.writeTo(out);
        } catch (IOException | SecurityException e) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return null;
        }
        try {
            return FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", target);
        } catch (IllegalArgumentException e) {
            // file_paths doesn't cover it. The bytes are written and that is the important half,
            // so report success without something to open.
            return Uri.fromFile(target);
        }
    }

    /**
     * {@code <title>_<timestamp>.<ext>} — the title so the file is recognisable in a folder of
     * them, the timestamp so exporting the same note twice doesn't collide (MediaStore would
     * silently rename the second one, and the legacy path would overwrite it).
     */
    private static String fileName(String noteTitle, String extension) {
        String base = sanitize(noteTitle);
        if (base.isEmpty()) base = FOLDER;
        return base + "_" + TimeStamps.fileStamp() + "." + extension;
    }

    /** Keeps letters, digits and spaces; everything else becomes an underscore. Deliberately
     *  conservative — the file lands on shared storage that may well be FAT-formatted. */
    private static String sanitize(String title) {
        if (title == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < title.length() && out.length() < MAX_TITLE_CHARS; i++) {
            char c = title.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            } else if (c == ' ' || c == '-' || c == '_') {
                // Collapsed, or a title like "Notes - Monday" comes out as "notes___monday".
                if (out.length() > 0 && out.charAt(out.length() - 1) != '_') out.append('_');
            }
        }
        // Trailing underscores read as a mistake next to the timestamp separator.
        while (out.length() > 0 && out.charAt(out.length() - 1) == '_') {
            out.setLength(out.length() - 1);
        }
        return out.toString().toLowerCase(Locale.US);
    }
}
