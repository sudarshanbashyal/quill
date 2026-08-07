package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import mse.quill.ui.whiteboard.WhiteboardView;

/**
 * Covers the paper styles, and the erasure they depend on.
 *
 * <p>The eraser used to paint opaque white strokes — invisible on a white board, which is how it
 * survived this long. On any other paper that is a white smear, so these tests are really about
 * the eraser clearing back to the background rather than covering it.
 */
@RunWith(AndroidJUnit4.class)
public class WhiteboardBackgroundTest {

    private static final int WIDTH  = 400;
    private static final int HEIGHT = 800;
    private static final int PAPER  = 0xFFFDF5E0;

    private WhiteboardView view;
    private long downTime;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        view = new WhiteboardView(context);
        view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, WIDTH, HEIGHT);
        view.setStrokeWidth(20f);
    }

    @Test
    public void anEmptyBoardExportsItsPaper() {
        view.setBackgroundStyle(WhiteboardView.BACKGROUND_PAPER);

        Bitmap exported = view.exportToBitmap();

        assertEquals(PAPER, exported.getPixel(exported.getWidth() / 2, exported.getHeight() / 2));
    }

    @Test
    public void theEraserClearsBackToThePaper_ratherThanPaintingWhiteOverIt() {
        view.setBackgroundStyle(WhiteboardView.BACKGROUND_PAPER);
        drawLine(WhiteboardView.TOOL_PEN, 100, 400, 300, 400);
        int inkBefore = countDarkPixels(view.exportToBitmap());
        assertTrue("the pen should have drawn something", inkBefore > 0);

        drawLine(WhiteboardView.TOOL_ERASER, 100, 400, 300, 400);
        Bitmap after = view.exportToBitmap();

        assertTrue("the eraser should have removed ink", countDarkPixels(after) < inkBefore);
        assertFalse("an eraser must not paint white onto warm paper", contains(after, Color.WHITE));
        assertTrue("what it uncovers is the paper", contains(after, PAPER));
    }

    @Test
    public void theEraserOnAPlainBoardStillLooksWhite() {
        view.setBackgroundStyle(WhiteboardView.BACKGROUND_WHITE);
        drawLine(WhiteboardView.TOOL_PEN, 100, 400, 300, 400);
        drawLine(WhiteboardView.TOOL_ERASER, 100, 400, 300, 400);

        // Same erasure, but the paper underneath happens to be white — the old behaviour by
        // coincidence rather than by painting.
        assertTrue(contains(view.exportToBitmap(), Color.WHITE));
    }

    @Test
    public void dotsAreDrawnOnTheDottedPaperAndNotOnTheOthers() {
        view.setBackgroundStyle(WhiteboardView.BACKGROUND_DOTS);
        drawLine(WhiteboardView.TOOL_PEN, 100, 400, 300, 400);
        int withDots = countNonWhitePixels(view.exportToBitmap());

        view.setBackgroundStyle(WhiteboardView.BACKGROUND_WHITE);
        int plain = countNonWhitePixels(view.exportToBitmap());

        assertTrue("dotted paper should add marks the plain one doesn't have", withDots > plain);
    }

    @Test
    public void aThumbnailIsTheSamePictureAsAnExport_justSmaller() {
        view.setBackgroundStyle(WhiteboardView.BACKGROUND_PAPER);
        drawLine(WhiteboardView.TOOL_PEN, 50, 100, 350, 700);

        Bitmap thumbnail = view.renderThumbnail(64);

        assertTrue("the longest side must respect the cap",
                Math.max(thumbnail.getWidth(), thumbnail.getHeight()) <= 64);
        assertTrue("a preview shows the board's paper, not a white rectangle",
                contains(thumbnail, PAPER));
        assertTrue("and the ink on it", countDarkPixels(thumbnail) > 0);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void drawLine(int tool, float x1, float y1, float x2, float y2) {
        view.setTool(tool);
        downTime = SystemClock.uptimeMillis();
        send(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1, 0));
        send(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE, (x1 + x2) / 2, (y1 + y2) / 2, 0));
        send(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE, x2, y2, 0));
        send(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, x2, y2, 0));
    }

    private void send(MotionEvent event) {
        view.onTouchEvent(event);
        event.recycle();
    }

    private static boolean contains(Bitmap bitmap, int colour) {
        for (int x = 0; x < bitmap.getWidth(); x++) {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                if (bitmap.getPixel(x, y) == colour) return true;
            }
        }
        return false;
    }

    private static int countDarkPixels(Bitmap bitmap) {
        int count = 0;
        for (int x = 0; x < bitmap.getWidth(); x++) {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                if (Color.red(bitmap.getPixel(x, y)) < 100) count++;
            }
        }
        return count;
    }

    private static int countNonWhitePixels(Bitmap bitmap) {
        int count = 0;
        for (int x = 0; x < bitmap.getWidth(); x++) {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                if (bitmap.getPixel(x, y) != Color.WHITE) count++;
            }
        }
        return count;
    }
}
