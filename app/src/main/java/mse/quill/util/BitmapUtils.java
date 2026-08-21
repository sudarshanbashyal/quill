package mse.quill.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import mse.quill.security.MediaFiles;

/** Decoding helpers shared by the image pipeline — capture, the inline segment, and the viewer. */
public final class BitmapUtils {

    /** Longest edge kept when an image is taken into the note. Well past what any phone screen
     *  can show, but small enough that a 12MP capture doesn't sit in memory at full size. */
    private static final int STORED_MAX_DIMENSION = 2048;
    private static final int STORED_JPEG_QUALITY = 90;

    private BitmapUtils() {}

    /** Decodes at roughly {@code maxWidth}, never the full image — a phone camera JPEG decoded at
     *  full resolution is tens of megabytes of heap for something being drawn a few hundred pixels
     *  wide. */
    public static Bitmap decodeSampled(String path, int maxWidth) {
        // Through MediaFiles rather than BitmapFactory.decodeFile, so an image belonging to a
        // locked collection decodes from its plaintext in memory. Nothing above this line knows
        // whether a given file is encrypted, which is the point — see MediaFiles.
        byte[] data = MediaFiles.readPlaintext(path);
        if (data == null) return null;
        return decodeSampled(data, maxWidth);
    }

    /** The same, for bytes already in hand. */
    public static Bitmap decodeSampled(byte[] data, int maxWidth) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);

        int sampleSize = 1;
        while (options.outWidth / sampleSize > maxWidth) {
            sampleSize *= 2;
        }

        options.inJustDecodeBounds = false;
        options.inSampleSize = sampleSize;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    /**
     * Rewrites a stored image upright, and bounded to {@link #STORED_MAX_DIMENSION}.
     *
     * <p>Cameras don't rotate pixels — they record how the phone was held in an EXIF tag and leave
     * the data as the sensor read it. {@code BitmapFactory} ignores that tag, so a photo taken in
     * portrait decodes sideways. Gallery picks hit the same problem, because importing copies the
     * file byte for byte and carries the tag with it.
     *
     * <p>Normalising once here, on the way in, means nothing downstream — the inline segment, the
     * viewer, the exported copy — has to know EXIF exists. The alternative, honouring the tag at
     * every draw, has to be repeated correctly in every consumer, including any added later.
     *
     * @return true if the file was rewritten; false leaves the original untouched, which is the
     *         right outcome for an image that was already upright and small enough.
     */
    public static boolean normaliseStoredImage(String path) {
        // Runs on the way in, before the file is ever encrypted, so this reads plaintext either
        // way — but it goes through the same door as every other decode so there is only one.
        byte[] data = MediaFiles.readPlaintext(path);
        if (data == null) return false;

        int rotation = rotationDegrees(path);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);

        boolean oversized = Math.max(bounds.outWidth, bounds.outHeight) > STORED_MAX_DIMENSION;
        if (rotation == 0 && !oversized) return false;

        Bitmap decoded = decodeSampled(data, STORED_MAX_DIMENSION);
        if (decoded == null) return false;

        Bitmap upright = decoded;
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            upright = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(),
                    matrix, true);
        }

        boolean written = false;
        try (FileOutputStream out = new FileOutputStream(new File(path))) {
            written = upright.compress(Bitmap.CompressFormat.JPEG, STORED_JPEG_QUALITY, out);
        } catch (IOException e) {
            written = false;
        } finally {
            if (upright != decoded) upright.recycle();
            decoded.recycle();
        }
        return written;
    }

    private static int rotationDegrees(String path) {
        try {
            int orientation = new ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException e) {
            return 0;
        }
    }
}
