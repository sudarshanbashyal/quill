package mse.quill;

import static org.junit.Assert.assertEquals;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mse.quill.data.model.WhiteboardText;
import mse.quill.ui.whiteboard.WhiteboardView;

/**
 * Covers text on a whiteboard: placed by a tap, drawn in canvas coordinates, and treated as an
 * item you add rather than an object you edit — so it counts towards the board's bounds, exports
 * with the ink, and goes away with undo or clear.
 */
@RunWith(AndroidJUnit4.class)
public class WhiteboardTextTest {

    private static final int WIDTH  = 400;
    private static final int HEIGHT = 800;

    private WhiteboardView view;
    private final List<float[]> placements = new ArrayList<>();
    private long downTime;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        view = new WhiteboardView(context);
        view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, WIDTH, HEIGHT);
        placements.clear();
        view.setTextPlacementListener((x, y) -> placements.add(new float[]{x, y}));
    }

    @Test
    public void aTapInTextModeAsksForAnEditorAtCanvasCoordinates() {
        view.setInputMode(WhiteboardView.MODE_TEXT);
        int scrollX = view.getScrollX();
        int scrollY = view.getScrollY();

        down(120, 240);
        up(120, 240);

        assertEquals(1, placements.size());
        assertEquals(scrollX + 120f, placements.get(0)[0], 0.5f);
        assertEquals(scrollY + 240f, placements.get(0)[1], 0.5f);
    }

    @Test
    public void aDragInTextModeIsNotAPlacement() {
        view.setInputMode(WhiteboardView.MODE_TEXT);

        down(120, 240);
        move(220, 340);
        up(220, 340);

        assertTrue("dragging is not tapping, so nothing should be placed", placements.isEmpty());
    }

    @Test
    public void textModeDrawsNothing() {
        List<Object> strokes = new ArrayList<>();
        view.setStrokeListener(stroke -> strokes.add(stroke));
        view.setInputMode(WhiteboardView.MODE_TEXT);

        down(50, 60);
        move(80, 90);
        up(80, 90);

        assertTrue(strokes.isEmpty());
    }

    @Test
    public void textCountsTowardsWhereTheBoardIs() {
        view.loadTexts(Collections.singletonList(textAt(3000f, 5000f, "Kernel trick")));
        view.centreOnContent();

        // A board with nothing but a label still has somewhere to centre on.
        assertTrue(view.getScrollX() < 3000 && 3000 < view.getScrollX() + WIDTH);
        assertTrue(view.getScrollY() < 5000 && 5000 < view.getScrollY() + HEIGHT);
    }

    @Test
    public void exportIncludesTextOnItsOwn() {
        view.loadTexts(Collections.singletonList(textAt(1000f, 1000f, "Kernel trick")));

        Bitmap exported = view.exportToBitmap();

        // Sized to the label plus padding rather than to the window, which is how a blank board
        // exports — so anything smaller than the window means the text was measured.
        assertTrue(exported.getWidth() < WIDTH);
        assertTrue(exported.getHeight() < HEIGHT);
    }

    @Test
    public void undoingRemovesOnlyTheItemNamed() {
        WhiteboardText first = textAt(100f, 100f, "first");
        WhiteboardText second = textAt(200f, 200f, "second");
        view.loadTexts(new ArrayList<>(java.util.Arrays.asList(first, second)));

        view.removeText(second.id);
        view.centreOnContent();

        // Only "first" is left, so the window centres on it rather than between the two.
        assertTrue(view.getScrollY() < 100 && 100 < view.getScrollY() + HEIGHT);
    }

    @Test
    public void clearingTakesTheTextWithIt() {
        view.loadTexts(Collections.singletonList(textAt(4000f, 4000f, "gone")));
        view.clearAll();

        // Nothing left to centre on, so the window goes home to the middle of the canvas.
        assertEquals((view.getCanvasSize() - WIDTH) / 2, view.getScrollX());
        assertEquals((view.getCanvasSize() - HEIGHT) / 2, view.getScrollY());
    }

    @Test
    public void emptyTextIsNotDrawnAndDoesNotMoveTheBoard() {
        int homeX = view.getScrollX();
        view.loadTexts(Collections.singletonList(textAt(9000f, 9000f, "")));
        view.centreOnContent();

        assertEquals("an empty label has no bounds to centre on", homeX, view.getScrollX());
    }

    private WhiteboardText textAt(float x, float y, String words) {
        WhiteboardText item = new WhiteboardText();
        item.id = java.util.UUID.randomUUID().toString();
        item.x = x;
        item.y = y;
        item.text = words;
        item.color = Color.BLACK;
        item.size = 28f;
        item.createdAt = System.currentTimeMillis();
        return item;
    }

    private void down(float x, float y) {
        downTime = SystemClock.uptimeMillis();
        send(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0));
    }

    private void move(float x, float y) {
        send(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE, x, y, 0));
    }

    private void up(float x, float y) {
        send(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, x, y, 0));
    }

    private void send(MotionEvent event) {
        view.onTouchEvent(event);
        event.recycle();
    }
}
