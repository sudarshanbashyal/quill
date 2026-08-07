package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import mse.quill.data.model.Stroke;
import mse.quill.ui.whiteboard.WhiteboardView;

/**
 * Covers the pannable canvas: one finger draws, two fingers move the window, and the window stops
 * at the canvas edges.
 *
 * <p>Instrumented rather than a plain JVM test because the behaviour *is* multi-pointer
 * {@link MotionEvent} handling — the emulator won't take injected multi-touch (SELinux blocks
 * writes to /dev/input), so the events are built here and handed to the view directly.
 */
@RunWith(AndroidJUnit4.class)
public class WhiteboardViewPanTest {

    private static final int WIDTH  = 400;
    private static final int HEIGHT = 800;

    private WhiteboardView view;
    private List<Stroke> completed;
    private long downTime;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        view = new WhiteboardView(context);
        view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, WIDTH, HEIGHT);

        completed = new ArrayList<>();
        view.setStrokeListener(stroke -> completed.add(stroke));
    }

    @Test
    public void aBoardOpensInTheMiddleOfTheCanvas() {
        // Not at a corner: there has to be room to draw in every direction.
        assertEquals(homeX(), view.getScrollX());
        assertEquals(homeY(), view.getScrollY());
    }

    @Test
    public void oneFingerDraws_andDoesNotMoveTheWindow() {
        down(10, 20);
        move(60, 90);
        up(60, 90);

        assertEquals(1, completed.size());
        assertEquals(homeX(), view.getScrollX());
        assertEquals(homeY(), view.getScrollY());
    }

    @Test
    public void twoFingerDragMovesTheWindowOppositeTheFingers() {
        // Start away from the origin, so the drag has room in both directions.
        panTo(200, 200);

        down(100, 100);
        pointerDown(new float[]{100, 200}, new float[]{100, 200});
        moveTwo(new float[]{60, 160}, new float[]{70, 170});
        up(60, 70);

        // Fingers moved 40 left and 30 up, so the window moves the same distance right and down.
        assertEquals(240, view.getScrollX());
        assertEquals(230, view.getScrollY());
    }

    @Test
    public void theMoveToolPansWithOneFinger_andDrawsNothing() {
        view.setInputMode(WhiteboardView.MODE_MOVE);
        int startX = view.getScrollX();
        int startY = view.getScrollY();

        down(200, 300);
        move(150, 260);
        up(150, 260);

        assertTrue("the move tool must not draw", completed.isEmpty());
        assertEquals(startX + 50, view.getScrollX());
        assertEquals(startY + 40, view.getScrollY());
    }

    @Test
    public void turningTheMoveToolOffGoesBackToDrawing() {
        view.setInputMode(WhiteboardView.MODE_MOVE);
        view.setInputMode(WhiteboardView.MODE_DRAW);

        down(10, 20);
        move(60, 90);
        up(60, 90);

        assertEquals(1, completed.size());
        assertEquals(homeX(), view.getScrollX());
        assertEquals(homeY(), view.getScrollY());
    }

    @Test
    public void aPalmLandingMidStrokeDoesNotInterruptTheStylus() {
        stylusDown(200, 300, MotionEvent.TOOL_TYPE_STYLUS, 1f);
        move(220, 320);
        // The hand holding the pen comes to rest on the screen.
        pointerDown(new float[]{220, 400}, new float[]{320, 600});
        moveTwo(new float[]{240, 400}, new float[]{340, 600});
        up(240, 340);

        assertEquals("the stylus stroke must survive the palm", 1, completed.size());
        assertEquals("a palm must not pan the canvas", homeX(), view.getScrollX());
        assertEquals(homeY(), view.getScrollY());
    }

    @Test
    public void theEraserEndOfAStylusErases_whateverToolIsSelected() {
        stylusDown(50, 50, MotionEvent.TOOL_TYPE_ERASER, 1f);
        move(60, 60);
        up(60, 60);

        assertEquals(1, completed.size());
        assertEquals(WhiteboardView.TOOL_ERASER, completed.get(0).tool);
    }

    @Test
    public void stylusPressureScalesTheStrokeWidth() {
        stylusDown(50, 50, MotionEvent.TOOL_TYPE_STYLUS, 1f);
        move(60, 60);
        up(60, 60);
        float atFullPressure = completed.get(0).width;

        completed.clear();
        stylusDown(50, 50, MotionEvent.TOOL_TYPE_STYLUS, 0.25f);
        move(60, 60);
        up(60, 60);

        assertTrue("a lighter touch should draw a thinner line",
                completed.get(0).width < atFullPressure);
    }

    @Test
    public void aFingerIsUnaffectedByThePressureItReports() {
        view.setStrokeWidth(7f);
        down(50, 50);
        move(60, 60);
        up(60, 60);

        // Fingers report pressure too, and it means something different — leave their width alone.
        assertEquals(7f, completed.get(0).width, 0.01f);
    }

    @Test
    public void theCanvasReportsWhereTheWindowSitsForTheScrollbars() {
        panTo(1000, 2000);

        assertEquals(view.getCanvasSize(), view.computeHorizontalScrollRange());
        assertEquals(view.getCanvasSize(), view.computeVerticalScrollRange());
        assertEquals(1000, view.computeHorizontalScrollOffset());
        assertEquals(2000, view.computeVerticalScrollOffset());
        assertEquals(WIDTH, view.computeHorizontalScrollExtent());
        assertEquals(HEIGHT, view.computeVerticalScrollExtent());
    }

    @Test
    public void theSecondFingerCancelsTheStrokeTheFirstStarted() {
        down(50, 50);
        move(55, 55);
        pointerDown(new float[]{55, 200}, new float[]{55, 200});
        moveTwo(new float[]{40, 185}, new float[]{40, 185});
        up(40, 40);

        assertTrue("a two-finger pan must not leave a stroke behind", completed.isEmpty());
    }

    @Test
    public void strokesLandInCanvasCoordinates_notWindowCoordinates() {
        panTo(300, 500);

        down(10, 20);
        move(11, 21);
        up(11, 21);

        assertEquals(1, completed.size());
        // The touch was 10,20 into a window scrolled to 300,500 — so 310,520 on the canvas.
        assertEquals(310f, completed.get(0).points.get(0).x, 0.5f);
        assertEquals(520f, completed.get(0).points.get(0).y, 0.5f);
    }

    @Test
    public void theWindowStopsAtTheCanvasEdges() {
        panTo(-5000, -5000);
        assertEquals(0, view.getScrollX());
        assertEquals(0, view.getScrollY());

        panTo(999_999, 999_999);
        assertEquals(view.getCanvasSize() - WIDTH, view.getScrollX());
        assertEquals(view.getCanvasSize() - HEIGHT, view.getScrollY());
    }

    @Test
    public void theCanvasIsTheSameSizeWhicheverWayUpTheDeviceIs() {
        int portrait = view.getCanvasSize();

        view.measure(View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, HEIGHT, WIDTH);

        // Sizing the canvas from the window instead put ink drawn in portrait outside the
        // landscape canvas, where no scroll position could reach it — the drawing vanished.
        assertEquals(portrait, view.getCanvasSize());
    }

    @Test
    public void inkDrawnInPortraitIsStillReachableInLandscape() {
        // Draw in the middle of the window, which on a fresh board is the middle of the canvas.
        down(200, 400);
        move(201, 401);
        up(201, 401);
        float inkX = completed.get(0).points.get(0).x;
        float inkY = completed.get(0).points.get(0).y;

        // Rotate: the view is re-laid out the other way up and the fragment reloads the strokes.
        List<Stroke> saved = new ArrayList<>(completed);
        view.measure(View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, HEIGHT, WIDTH);
        view.loadStrokes(saved);

        assertTrue("ink must stay inside the canvas", inkX <= view.getCanvasSize());
        assertTrue("ink must stay inside the canvas", inkY <= view.getCanvasSize());
        assertTrue("ink must be in the window after rotating",
                view.getScrollX() <= inkX && inkX <= view.getScrollX() + HEIGHT);
        assertTrue("ink must be in the window after rotating",
                view.getScrollY() <= inkY && inkY <= view.getScrollY() + WIDTH);
    }

    @Test
    public void centreOnContentBringsTheWindowBackToTheInk() {
        // Draw a dot near the far corner, then look somewhere else entirely.
        panTo(2000, 4000);
        down(200, 400);
        move(201, 401);
        up(201, 401);
        assertEquals(1, completed.size());

        panTo(0, 0);
        view.centreOnContent();

        // The dot sits at canvas 2200,4400 — centred means it lands mid-window.
        assertEquals(WIDTH / 2f, 2200.5f - view.getScrollX(), 2f);
        assertEquals(HEIGHT / 2f, 4400.5f - view.getScrollY(), 2f);
    }

    @Test
    public void centreOnAnEmptyBoardReturnsToTheMiddle() {
        panTo(1000, 1000);
        view.centreOnContent();

        assertEquals(homeX(), view.getScrollX());
        assertEquals(homeY(), view.getScrollY());
    }

    @Test
    public void loadingStrokesOpensTheWindowOnThem() {
        Stroke faraway = new Stroke();
        faraway.id = "s1";
        faraway.tool = WhiteboardView.TOOL_PEN;
        faraway.width = 4f;
        faraway.points = new ArrayList<>();
        faraway.points.add(new android.graphics.PointF(1500f, 3000f));
        faraway.points.add(new android.graphics.PointF(1510f, 3010f));

        List<Stroke> strokes = new ArrayList<>();
        strokes.add(faraway);
        view.loadStrokes(strokes);

        // Reopening a board must not land on blank canvas: the ink's centre sits in the window.
        assertTrue(view.getScrollX() < 1505 && view.getScrollX() + WIDTH > 1505);
        assertTrue(view.getScrollY() < 3005 && view.getScrollY() + HEIGHT > 3005);
    }

    @Test
    public void exportCoversTheWholeDrawing_notJustTheVisibleWindow() {
        // Two dots two windows apart, so no single window could contain both.
        down(50, 50);
        move(51, 51);
        up(51, 51);
        panTo(WIDTH * 2, HEIGHT * 2);
        down(50, 50);
        move(51, 51);
        up(51, 51);

        android.graphics.Bitmap exported = view.exportToBitmap();
        assertTrue("export should span both dots", exported.getWidth() > WIDTH);
        assertTrue("export should span both dots", exported.getHeight() > HEIGHT);
    }

    @Test
    public void clearingComesBackToTheMiddle() {
        panTo(1200, 2400);
        view.clearAll();

        assertEquals(homeX(), view.getScrollX());
        assertEquals(homeY(), view.getScrollY());
    }

    // ── Gesture helpers ───────────────────────────────────────────────────────

    /** Where an empty board sits: the middle of the canvas, half the scrollable range in. */
    private int homeX() { return (view.getCanvasSize() - WIDTH) / 2; }

    private int homeY() { return (view.getCanvasSize() - HEIGHT) / 2; }

    /** Moves the window with a two-finger drag, since there is no public scroll API to call. */
    private void panTo(int x, int y) {
        int dx = x - view.getScrollX();
        int dy = y - view.getScrollY();
        // Fingers move opposite the window, and start mid-window so the drag has room.
        float startX = WIDTH / 2f;
        float startY = HEIGHT / 2f;
        down(startX, startY);
        pointerDown(new float[]{startX, startX}, new float[]{startY, startY + 10});
        moveTwo(new float[]{startX - dx, startX - dx},
                new float[]{startY - dy, startY + 10 - dy});
        up(startX - dx, startY - dy);
        completed.clear();
    }

    /** A stylus touching down, with the tool type and pressure a real pen reports. */
    private void stylusDown(float x, float y, int toolType, float pressure) {
        downTime = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;
        props.toolType = toolType;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = pressure;
        coords.size = 1f;
        send(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 1,
                new MotionEvent.PointerProperties[]{props},
                new MotionEvent.PointerCoords[]{coords},
                0, 0, 1f, 1f, 0, 0, 0, 0));
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

    private void pointerDown(float[] xs, float[] ys) {
        int action = MotionEvent.ACTION_POINTER_DOWN
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        send(multiPointer(action, xs, ys));
    }

    private void moveTwo(float[] xs, float[] ys) {
        send(multiPointer(MotionEvent.ACTION_MOVE, xs, ys));
    }

    private MotionEvent multiPointer(int action, float[] xs, float[] ys) {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[xs.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[xs.length];
        for (int i = 0; i < xs.length; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = xs[i];
            coords[i].y = ys[i];
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, xs.length,
                props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0);
    }

    private void send(MotionEvent event) {
        view.onTouchEvent(event);
        event.recycle();
    }
}
