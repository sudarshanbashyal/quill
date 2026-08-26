package mse.quill.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import mse.quill.security.MediaFiles;

/**
 * Puts a picture into the device's shared Pictures collection, under a {@code Quill} album, so it
 * survives the note or board it came from and shows up in the gallery.
 *
 * <p>Two ways in: a note's embedded image, which is a file on disk and may be encrypted, and a
 * whiteboard, which is a {@link Bitmap} that only exists for the length of the export. They share
 * {@link #saveToPictures(Context, String, String, Body)} because the MediaStore dance underneath is
 * the awkward part, and it is the same dance either way — see the version gates in there for why
 * writing it twice went wrong once already.
 */
public final class ImageExporter {

    private static final String ALBUM = "Quill";

    private ImageExporter() {}

    /** Writes the actual bytes, once a pending MediaStore row is waiting for them. */
    private interface Body {
        void writeTo(OutputStream out) throws IOException;
    }

    private static String exportName(String extension) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return ALBUM + "_" + stamp + extension;
    }

    /**
     * Whether saving needs {@code WRITE_EXTERNAL_STORAGE} first. Scoped storage (API 29+) lets an
     * app write its own entries into the shared collection unprompted; below that, adding to
     * MediaStore is a permissioned operation.
     */
    public static boolean requiresStoragePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
    }

    /**
     * Copies a note's embedded image out. Runs disk I/O — call from a background thread.
     *
     * @return the name it was saved under, or null if nothing was written.
     */
    public static String saveToPictures(Context context, String sourcePath) {
        File source = new File(sourcePath);
        if (!source.exists()) return null;

        // Decrypted on the way out, not copied byte for byte: an image in a locked collection is
        // ciphertext on disk, and "save to Photos" that produced an unopenable file would be a
        // strange way to find that out. Saving one out is a deliberate act by someone who has
        // already unlocked the collection, so it leaves as an ordinary picture.
        byte[] plaintext = MediaFiles.readPlaintext(source.getAbsolutePath());
        if (plaintext == null) return null;

        // Not source.getName(): that's the internal "img_<uuid>.jpg", which is what the user
        // would then see sitting in their gallery.
        return saveToPictures(context, exportName(".jpg"), "image/jpeg", out -> out.write(plaintext));
    }

    /**
     * Saves a rendered whiteboard as a PNG. Runs disk I/O — call from a background thread.
     *
     * @return the name it was saved under, or null if nothing was written.
     */
    public static String savePngToPictures(Context context, Bitmap bitmap) {
        if (bitmap == null) return null;
        return saveToPictures(context, exportName(".png"), "image/png",
                out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
    }

    /** Where the two above meet: create a row, write into it, publish it. */
    private static String saveToPictures(Context context, String displayName, String mimeType,
                                         Body body) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
        // Both of these are API 29 columns. Setting them unconditionally is what broke the
        // whiteboard's own copy of this method on API 26-28, which is why there is only one copy
        // now — see memory/refactoring_plan.md R10.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + ALBUM);
            // Hides the row from other apps until the bytes are actually written, so a gallery
            // scanning mid-copy never shows a half-written image.
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        ContentResolver resolver = context.getContentResolver();
        Uri target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (target == null) return null;

        try (OutputStream out = resolver.openOutputStream(target)) {
            if (out == null) throw new IOException("no output stream for " + target);
            body.writeTo(out);
        } catch (IOException | SecurityException e) {
            // Leaving the row behind would show as a permanently empty image in the gallery.
            resolver.delete(target, null, null);
            return null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues published = new ContentValues();
            published.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(target, published, null, null);
        }
        return displayName;
    }

    /** Where a saved picture ends up, for telling the user. */
    public static String albumPath() {
        return Environment.DIRECTORY_PICTURES + File.separator + ALBUM;
    }
}
