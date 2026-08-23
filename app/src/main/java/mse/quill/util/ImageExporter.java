package mse.quill.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Copies a note's image out of Quill's private storage into the device's shared Pictures
 * collection, so it survives the note and shows up in the gallery.
 */
public final class ImageExporter {

    private static final String ALBUM = "Quill";

    private ImageExporter() {}

    private static String exportName() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return ALBUM + "_" + stamp + ".jpg";
    }

    /**
     * Whether saving needs {@code WRITE_EXTERNAL_STORAGE} first. Scoped storage (API 29+) lets an
     * app write its own entries into the shared collection unprompted; below that, adding to
     * MediaStore is a permissioned operation.
     */
    public static boolean requiresStoragePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
    }

    /** Runs disk I/O — call from a background thread. */
    public static boolean saveToPictures(Context context, String sourcePath) {
        File source = new File(sourcePath);
        if (!source.exists()) return false;

        ContentValues values = new ContentValues();
        // Not source.getName(): that's the internal "img_<uuid>.jpg", which is what the user
        // would then see sitting in their gallery.
        values.put(MediaStore.Images.Media.DISPLAY_NAME, exportName());
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + ALBUM);
            // Hides the row from other apps until the bytes are actually written, so a gallery
            // scanning mid-copy never shows a half-written image.
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        ContentResolver resolver = context.getContentResolver();
        Uri target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (target == null) return false;

        // Decrypted on the way out, not copied byte for byte: an image in a locked collection is
        // ciphertext on disk, and "save to Photos" that produced an unopenable file would be a
        // strange way to find that out. Saving one out is a deliberate act by someone who has
        // already unlocked the collection, so it leaves as an ordinary picture.
        byte[] plaintext = mse.quill.security.MediaFiles.readPlaintext(source.getAbsolutePath());
        if (plaintext == null) return false;

        try (OutputStream out = resolver.openOutputStream(target)) {
            if (out == null) throw new IOException("no output stream for " + target);
            out.write(plaintext);
        } catch (IOException | SecurityException e) {
            // Leaving the row behind would show as a permanently empty image in the gallery.
            resolver.delete(target, null, null);
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues published = new ContentValues();
            published.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(target, published, null, null);
        }
        return true;
    }
}
