package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.ExifInterface;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import mse.quill.util.BitmapUtils;

/** Covers orientation normalisation — the reason camera photos used to appear sideways. */
@RunWith(AndroidJUnit4.class)
public class BitmapUtilsTest {

    private File dir;

    @Before
    public void setUp() {
        dir = new File(ApplicationProvider.getApplicationContext().getCacheDir(), "bitmap-tests");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** Writes a landscape JPEG (wider than tall) with the given EXIF orientation tag. */
    private File writeJpeg(String name, int width, int height, int exifOrientation) throws IOException {
        File file = new File(dir, name);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.RED);
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        bitmap.recycle();

        if (exifOrientation != ExifInterface.ORIENTATION_NORMAL) {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(exifOrientation));
            exif.saveAttributes();
        }
        return file;
    }

    private static int[] dimensionsOf(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        return new int[]{bounds.outWidth, bounds.outHeight};
    }

    /** The camera bug: a portrait photo is stored landscape with a "rotate 90" tag, and
     *  BitmapFactory ignores the tag. After normalising, the pixels themselves must be upright. */
    @Test
    public void rotatedImageIsUprightedOnDisk() throws Exception {
        File file = writeJpeg("rotate90.jpg", 200, 100, ExifInterface.ORIENTATION_ROTATE_90);
        assertEquals("precondition: stored landscape", 200, dimensionsOf(file)[0]);

        assertTrue("file should have been rewritten",
                BitmapUtils.normaliseStoredImage(file.getAbsolutePath()));

        int[] size = dimensionsOf(file);
        assertEquals("width and height should have swapped", 100, size[0]);
        assertEquals(200, size[1]);
    }

    @Test
    public void rotate270IsAlsoHandled() throws Exception {
        File file = writeJpeg("rotate270.jpg", 200, 100, ExifInterface.ORIENTATION_ROTATE_270);
        assertTrue(BitmapUtils.normaliseStoredImage(file.getAbsolutePath()));

        int[] size = dimensionsOf(file);
        assertEquals(100, size[0]);
        assertEquals(200, size[1]);
    }

    /** 180 turns the image without swapping the axes, so only the tag should disappear. */
    @Test
    public void rotate180KeepsDimensions() throws Exception {
        File file = writeJpeg("rotate180.jpg", 200, 100, ExifInterface.ORIENTATION_ROTATE_180);
        assertTrue(BitmapUtils.normaliseStoredImage(file.getAbsolutePath()));

        int[] size = dimensionsOf(file);
        assertEquals(200, size[0]);
        assertEquals(100, size[1]);
    }

    /** Re-encoding an image that needs nothing done would cost quality for no reason. */
    @Test
    public void uprightImageIsLeftUntouched() throws Exception {
        File file = writeJpeg("upright.jpg", 200, 100, ExifInterface.ORIENTATION_NORMAL);
        long lengthBefore = file.length();

        assertFalse("no rewrite expected",
                BitmapUtils.normaliseStoredImage(file.getAbsolutePath()));
        assertEquals("file should be byte-identical", lengthBefore, file.length());
    }

    /** Once normalised, no rotating tag may survive — otherwise a viewer that *does* honour EXIF
     *  would turn the already-upright pixels a second time. (Re-encoding drops the EXIF block
     *  entirely, so the tag reads back as UNDEFINED rather than NORMAL; both mean "as-is".) */
    @Test
    public void orientationTagNoLongerRotatesAfterNormalising() throws Exception {
        File file = writeJpeg("tag.jpg", 200, 100, ExifInterface.ORIENTATION_ROTATE_90);
        BitmapUtils.normaliseStoredImage(file.getAbsolutePath());

        int orientation = new ExifInterface(file.getAbsolutePath()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        assertTrue("unexpected rotating orientation: " + orientation,
                orientation != ExifInterface.ORIENTATION_ROTATE_90
                        && orientation != ExifInterface.ORIENTATION_ROTATE_180
                        && orientation != ExifInterface.ORIENTATION_ROTATE_270);
    }

    @Test
    public void oversizedImageIsBounded() throws Exception {
        File file = writeJpeg("huge.jpg", 4000, 3000, ExifInterface.ORIENTATION_NORMAL);
        assertTrue("an oversized image should be rewritten smaller",
                BitmapUtils.normaliseStoredImage(file.getAbsolutePath()));

        int[] size = dimensionsOf(file);
        assertTrue("longest edge should be bounded, was " + size[0] + "x" + size[1],
                Math.max(size[0], size[1]) <= 2048);
        assertEquals("aspect ratio should be preserved", 4.0 / 3.0, (double) size[0] / size[1], 0.01);
    }

    @Test
    public void decodeSampledStaysUnderRequestedWidth() throws Exception {
        File file = writeJpeg("sample.jpg", 1600, 800, ExifInterface.ORIENTATION_NORMAL);

        Bitmap decoded = BitmapUtils.decodeSampled(file.getAbsolutePath(), 400);
        assertNotNull(decoded);
        assertTrue("sampled width " + decoded.getWidth() + " should be <= 400",
                decoded.getWidth() <= 400);
        decoded.recycle();
    }
}
