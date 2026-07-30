package mse.quill.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

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
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);

        int sampleSize = 1;
        while (options.outWidth / sampleSize > maxWidth) {
            sampleSize *= 2;
        }

        options.inJustDecodeBounds = false;
        options.inSampleSize = sampleSize;
        return BitmapFactory.decodeFile(path, options);
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
        int rotation = rotationDegrees(path);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);

        boolean oversized = Math.max(bounds.outWidth, bounds.outHeight) > STORED_MAX_DIMENSION;
        if (rotation == 0 && !oversized) return false;

        Bitmap decoded = decodeSampled(path, STORED_MAX_DIMENSION);
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
