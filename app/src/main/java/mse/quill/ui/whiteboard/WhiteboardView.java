package mse.quill.ui.whiteboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;

import mse.quill.data.model.Stroke;
import mse.quill.data.model.WhiteboardText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WhiteboardView
 *
 * WHAT THIS FILE DOES:
 * A custom Android View that acts as the drawing canvas. It listens to
 * finger touch events and converts them into Stroke objects (a list of points),
 * renders them on screen, and notifies a listener (WhiteboardFragment) whenever
 * a stroke is completed so it can be saved to the database.
 *
 * WHY A CUSTOM VIEW (not a library):
 * Full control over touch handling, smooth curve rendering, and direct
 * integration with our Stroke/StrokeRepository model without pulling in an external
 * drawing library.
 *
 * THE CANVAS IS BIGGER THAN THE SCREEN:
 * Strokes are stored in canvas coordinates and the view shows a screen-sized
 * window onto them, moved with a two-finger drag. One finger always draws, so
 * there is no mode to switch between. A board opens in the middle of the canvas
 * rather than at a corner, so there is room to work in every direction. The canvas
 * is a fixed square that does not depend on this view's size — see getCanvasSize()
 * for why that matters on rotation — and CANVAS_SCREENS for why it is bounded.
 *
 * Tool constants:
 *  TOOL_PEN         = 0
 *  TOOL_ERASER      = 1
 *  TOOL_HIGHLIGHTER = 2
 */
public class WhiteboardView extends View {

    public static final int TOOL_PEN         = 0;
    public static final int TOOL_ERASER      = 1;
    public static final int TOOL_HIGHLIGHTER = 2;

    /**
     * What a single finger does. Separate from the tool constants above, which are persisted with
     * every stroke: moving the canvas and placing text produce no stroke, so they have no business
     * in that column.
     */
    /** Paper styles. Stored per board on {@code whiteboards.background}. */
    public static final int BACKGROUND_WHITE = 0;
    public static final int BACKGROUND_PAPER = 1;
    public static final int BACKGROUND_DOTS  = 2;

    /** Warm off-white, the colour of cheap notepaper — easier to look at than a lit white screen. */
    private static final int PAPER_COLOUR = 0xFFFDF5E0;
    private static final int DOT_COLOUR   = 0xFFC4C4C4;
    private static final float DOT_SPACING_DP = 26f;
    private static final float DOT_RADIUS_DP  = 1.4f;

    public static final int MODE_DRAW = 0;
    public static final int MODE_MOVE = 1;
    public static final int MODE_TEXT = 2;

    /**
     * How many screens across the canvas is — see {@link #getCanvasSize()} for which screen
     * dimension that multiplies, and why it is a square.
     *
     * <p>Bounded rather than infinite because there is no zoom: on an endless canvas a drawing
     * panned away from is somewhere you cannot see and have no overview to find it from — the
     * Centre button would be the only way back. Ten screens is far more room than a lecture's
     * worth of notes and still has an edge to stop at.
     */
    private static final int CANVAS_SCREENS = 10;

    /**
     * Longest side of an exported PNG. Export covers everything drawn rather than the visible
     * window, and the full canvas at phone resolution would be hundreds of megapixels, so a
     * drawing spread that wide is scaled down to fit instead of allocated at full size.
     */
    private static final int MAX_EXPORT_PX = 4096;

    /** Breathing room around the ink in an export, so strokes don't touch the image edge. */
    private static final float EXPORT_PADDING = 24f;

    /**
     * Bounds on how far a stylus's pressure may scale a stroke's width. Neutral at 1.0 — the
     * pressure a firm touch reports — so a light stroke thins and a hard one thickens, and the
     * width picked in the rail stays the width you get from ordinary pressure.
     */
    private static final float MIN_PRESSURE_SCALE = 0.5f;
    private static final float MAX_PRESSURE_SCALE = 1.5f;

    /** Told where a tap landed while in MODE_TEXT, so the fragment can put an editor there. */
    public interface TextPlacementListener {
        void onTextPlacementRequested(float canvasX, float canvasY);
    }

    // ── State ───────────────────────────────────────────────────────────────
    private final List<WhiteboardText> texts = new ArrayList<>();
    private final List<Stroke>      committedStrokes = new ArrayList<>();
    private final Map<String, Path> strokePaths      = new HashMap<>();
    private       Stroke            currentStroke;
    private       Path              currentPath      = new Path();

    /** True while the gesture in progress is moving the canvas rather than drawing. */
    private boolean panning;
    /** True when that pan is being driven by one finger (the Move tool) rather than two. */
    private boolean oneFingerPan;
    private int     inputMode = MODE_DRAW;
    private float   lastFocusX;
    private float   lastFocusY;
    /** Where a MODE_TEXT gesture went down, to tell a tap from a drag. */
    private float   downX;
    private float   downY;
    /** True while a stylus is drawing, so a palm landing on the screen is ignored until it lifts. */
    private boolean stylusStroke;
    /** False until the window has been placed on the canvas, which needs a measured size. */
    private boolean positioned;
    private int     canvasSize;

    private int    currentTool  = TOOL_PEN;
    private int    currentColor = Color.BLACK;
    private float  currentWidth = 6f;
    private String localDeviceId = "local-user"; // single-device placeholder

    private final Paint penPaint         = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eraserPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlighterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint         = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint          = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int backgroundStyle = BACKGROUND_WHITE;

    public interface StrokeListener {
        void onStrokeComplete(Stroke stroke);
    }
    private StrokeListener strokeListener;
    private TextPlacementListener textPlacementListener;

    // ── Constructors (required for View inflation from XML) ───────────────────
    public WhiteboardView(Context context) {
        super(context);
        init();
    }

    public WhiteboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WhiteboardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setBackgroundColor(Color.WHITE);

        penPaint.setStyle(Paint.Style.STROKE);
        penPaint.setStrokeJoin(Paint.Join.ROUND);
        penPaint.setStrokeCap(Paint.Cap.ROUND);

        eraserPaint.setStyle(Paint.Style.STROKE);
        eraserPaint.setStrokeJoin(Paint.Join.ROUND);
        eraserPaint.setStrokeCap(Paint.Cap.ROUND);
        eraserPaint.setColor(Color.WHITE);

        highlighterPaint.setStyle(Paint.Style.STROKE);
        highlighterPaint.setStrokeJoin(Paint.Join.ROUND);
        highlighterPaint.setStrokeCap(Paint.Cap.SQUARE);
        highlighterPaint.setAlpha(80);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(DOT_COLOUR);

        // Real erasure, rather than a white stroke pretending to be one. A white line is invisible
        // on a white board, which is why this went unnoticed — on paper or dots it would smear.
        eraserPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
    }

    // ── Public API used by WhiteboardFragment ──────────────────────────────────

    public void setStrokeListener(StrokeListener listener) {
        this.strokeListener = listener;
    }

    public void setTextPlacementListener(TextPlacementListener listener) {
        this.textPlacementListener = listener;
    }

    /** Load a board's text items, the way loadStrokes loads its strokes. */
    public void loadTexts(List<WhiteboardText> items) {
        texts.clear();
        texts.addAll(items);
        invalidate();
    }

    /** Show a text item that has just been typed. Persisting it is the fragment's job. */
    public void addText(WhiteboardText item) {
        for (WhiteboardText existing : texts) {
            if (existing.id.equals(item.id)) return; // already applied — dedupe by id
        }
        texts.add(item);
        invalidate();
    }

    /** Remove one text item (used by Undo). */
    public void removeText(String id) {
        for (int i = texts.size() - 1; i >= 0; i--) {
            if (texts.get(i).id.equals(id)) {
                texts.remove(i);
                break;
            }
        }
        invalidate();
    }

    public void setTool(int tool)          { this.currentTool = tool; }

    /**
     * Chooses what one finger does: draw, move the canvas, or place text.
     *
     * <p>Two-finger panning stays available in every mode, so Move is for working one-handed rather
     * than the only way to get around.
     */
    public void setInputMode(int mode) { this.inputMode = mode; }

    /** Chooses the paper: plain white, warm off-white, or dotted. */
    public void setBackgroundStyle(int style) {
        this.backgroundStyle = style;
        invalidate();
    }

    public int getBackgroundStyle() { return backgroundStyle; }
    public void setColor(int color)        { this.currentColor = color; }
    public void setStrokeWidth(float width){ this.currentWidth = width; }

    /**
     * Load previously saved strokes from the database when the screen opens, and open the window
     * on them. Without that a board drawn away from the origin would reopen on blank canvas, with
     * nothing on screen to say which way its drawing lies. Boards drawn before the canvas could
     * pan are unaffected: their ink is a screen wide at the origin, which centres to the origin.
     */
    public void loadStrokes(List<Stroke> strokes) {
        committedStrokes.clear();
        strokePaths.clear();
        for (Stroke s : strokes) {
            committedStrokes.add(s);
            buildPathForStroke(s);
        }
        // Before the first layout there is no window size to centre within, so onSizeChanged does it.
        if (getWidth() > 0) {
            positioned = true;
            centreOnContent();
        }
        invalidate(); // triggers onDraw()
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (w == 0 || h == 0) return;

        if (!positioned) {
            positioned = true;
            centreOnContent();
            return;
        }
        // Hiding the tool rail widens the window, which changes how far there is left to scroll.
        scrollTo(clamp(getScrollX(), maxScrollX()), clamp(getScrollY(), maxScrollY()));
    }

    /**
     * Adds one stroke someone else just drew, arriving over a live collaboration session — unlike
     * {@link #loadStrokes}, this neither clears what's already here nor re-centres, since drawing
     * shouldn't yank the window out from under whoever is looking at it.
     */
    public void addStroke(Stroke stroke) {
        for (Stroke existing : committedStrokes) {
            if (existing.id.equals(stroke.id)) return; // already applied — dedupe by id
        }
        committedStrokes.add(stroke);
        buildPathForStroke(stroke);
        invalidate();
    }

    /** Remove one stroke (used by Undo). */
    public void removeStroke(String strokeId) {
        committedStrokes.removeIf(s -> s.id.equals(strokeId));
        strokePaths.remove(strokeId);
        invalidate();
    }

    /** Wipe the canvas (used by Clear button), and come back to the middle of it. */
    public void clearAll() {
        committedStrokes.clear();
        strokePaths.clear();
        texts.clear();
        currentPath.reset();
        centreOnContent();
        invalidate();
    }

    /**
     * Render everything drawn into a Bitmap (used by Export).
     *
     * <p>Everything, not the visible window: once the canvas is larger than the screen, exporting
     * what happens to be on screen would silently crop the drawing. An empty board still exports a
     * screen-sized blank, so the button does something predictable before anything is drawn.
     */
    public Bitmap exportToBitmap() {
        return renderToBitmap(MAX_EXPORT_PX);
    }

    /**
     * The same picture as {@link #exportToBitmap()}, rendered small enough for a list.
     *
     * <p>Shares the export path rather than drawing its own version, so a card shows what the board
     * actually looks like — same paper, same erasures, same text. {@code maxPx} caps the longest
     * side, which for a thumbnail matters: a drawing spread across the canvas would otherwise be
     * allocated at export resolution just to be shrunk into a card.
     */
    public Bitmap renderThumbnail(int maxPx) {
        return renderToBitmap(maxPx);
    }

    private Bitmap renderToBitmap(int maxPx) {
        RectF ink = contentBounds();
        if (ink == null) {
            Bitmap blank = Bitmap.createBitmap(Math.max(getWidth(), 1), Math.max(getHeight(), 1),
                    Bitmap.Config.ARGB_8888);
            drawBackground(new Canvas(blank), 0, 0, getWidth(), getHeight());
            return blank;
        }

        ink.inset(-EXPORT_PADDING, -EXPORT_PADDING);
        ink.intersect(0f, 0f, getCanvasSize(), getCanvasSize());
        float scale = Math.min(1f, maxPx / Math.max(ink.width(), ink.height()));

        Bitmap bitmap = Bitmap.createBitmap(
                Math.max(1, Math.round(ink.width() * scale)),
                Math.max(1, Math.round(ink.height() * scale)),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(scale, scale);
        canvas.translate(-ink.left, -ink.top);
        drawBackground(canvas, ink.left, ink.top, ink.right, ink.bottom);
        drawStrokes(canvas);
        return bitmap;
    }

    /**
     * Move the window back to the drawing — the way out of a corner of a canvas ten screens wide.
     * Centres on the ink, or returns to the middle of the canvas when there is none.
     */
    public void centreOnContent() {
        RectF ink = contentBounds();
        if (ink == null) {
            scrollTo(maxScrollX() / 2, maxScrollY() / 2);
            return;
        }
        scrollTo(clamp(Math.round(ink.centerX() - getWidth() / 2f), maxScrollX()),
                 clamp(Math.round(ink.centerY() - getHeight() / 2f), maxScrollY()));
        awakenScrollBars();
    }

    // ── Touch handling ────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                panning = oneFingerPan = inputMode == MODE_MOVE;
                stylusStroke = false;
                if (panning) {
                    lastFocusX = event.getX();
                    lastFocusY = event.getY();
                } else if (inputMode == MODE_TEXT) {
                    // Placement is a tap, so nothing happens until the finger lifts.
                    downX = event.getX();
                    downY = event.getY();
                } else {
                    stylusStroke = isStylus(event.getToolType(0));
                    startStroke(worldX(event.getX()), worldY(event.getY()),
                            event.getToolType(0), event.getButtonState(), event.getPressure(0));
                }
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                // Palm rejection: while the stylus is drawing, the hand resting on the screen is
                // not a second finger asking to pan — it's the hand holding the pen.
                if (stylusStroke) return true;
                // A second finger means the gesture was a pan all along, so throw away what the
                // first one had started rather than leaving a stray tick behind on the canvas.
                discardCurrentStroke();
                panning = true;
                oneFingerPan = false;
                lastFocusX = focusX(event);
                lastFocusY = focusY(event);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (panning) {
                    // A two-finger pan follows the midpoint, so it waits for both to be down:
                    // reading one finger's position as the midpoint would jump the canvas.
                    if (oneFingerPan || event.getPointerCount() >= 2) {
                        float focusX = focusX(event);
                        float focusY = focusY(event);
                        panBy(lastFocusX - focusX, lastFocusY - focusY);
                        lastFocusX = focusX;
                        lastFocusY = focusY;
                    }
                    return true;
                }
                if (inputMode == MODE_TEXT) return true;
                // Historical points give smoother lines on fast movement
                int historySize = event.getHistorySize();
                for (int i = 0; i < historySize; i++) {
                    continueStroke(worldX(event.getHistoricalX(i)), worldY(event.getHistoricalY(i)));
                }
                continueStroke(worldX(event.getX()), worldY(event.getY()));
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                // Panning holds until every finger is up. Lifting one of two mid-drag shouldn't
                // start a stroke from wherever the other one happens to be resting.
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stylusStroke = false;
                if (panning) {
                    panning = false;
                    return true;
                }
                if (inputMode == MODE_TEXT) {
                    boolean tapped = event.getActionMasked() == MotionEvent.ACTION_UP
                            && Math.hypot(event.getX() - downX, event.getY() - downY) <= touchSlop();
                    if (tapped && textPlacementListener != null) {
                        textPlacementListener.onTextPlacementRequested(
                                worldX(event.getX()), worldY(event.getY()));
                    }
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    // The gesture was taken away rather than finished: a back-swipe from the edge,
                    // the notification shade coming down, a parent view deciding the gesture was
                    // its own. Grouped with ACTION_UP, this committed whatever the swipe had drawn
                    // — so backing out of a board with the system gesture left a stray line on it,
                    // and the board saved. A cancel means the stroke never happened, which is the
                    // same conclusion the two-finger case above reaches by the same call.
                    discardCurrentStroke();
                } else {
                    finishStroke(worldX(event.getX()), worldY(event.getY()));
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    /**
     * Touch coordinates are relative to the window; strokes are stored relative to the canvas.
     * The scroll offset between them is what a pan changes.
     */
    private float worldX(float viewX) { return viewX + getScrollX(); }

    private float worldY(float viewY) { return viewY + getScrollY(); }

    /** Midpoint of the first two pointers — a two-finger pan follows it rather than either finger. */
    private float focusX(MotionEvent event) {
        return event.getPointerCount() >= 2 ? (event.getX(0) + event.getX(1)) / 2f : event.getX(0);
    }

    private float focusY(MotionEvent event) {
        return event.getPointerCount() >= 2 ? (event.getY(0) + event.getY(1)) / 2f : event.getY(0);
    }

    private void panBy(float dx, float dy) {
        scrollTo(clamp(Math.round(getScrollX() + dx), maxScrollX()),
                 clamp(Math.round(getScrollY() + dy), maxScrollY()));
        awakenScrollBars();
    }

    /**
     * Whether anything at all is on this board — committed strokes or text boxes.
     *
     * <p>Answered from what this view holds rather than from the database, and that is the point:
     * strokes are written on their own unordered threads, so a caller leaving the screen a moment
     * after the last one was drawn could ask the database and be told the board is empty while the
     * insert is still in flight. This can't be wrong that way — a stroke reaches
     * {@link #committedStrokes} before anyone is told to save it.
     */
    public boolean hasContent() {
        return !committedStrokes.isEmpty() || !texts.isEmpty();
    }

    private void discardCurrentStroke() {
        if (currentStroke == null) return;
        currentStroke = null;
        currentPath = new Path();
        invalidate();
    }

    // ── Canvas extent ─────────────────────────────────────────────────────────

    /**
     * Side of the square canvas, in pixels.
     *
     * <p>Measured from the display's <em>shorter</em> edge rather than from this view, because that
     * is the one screen dimension a rotation doesn't change. Sizing the canvas from the window
     * instead — which is what this did first — moves every bound when the device turns: ink drawn
     * in the middle of a portrait canvas sits below the bottom edge of the landscape one, out of
     * reach of any scroll position, and the drawing simply vanishes on rotation.
     */
    public int getCanvasSize() {
        if (canvasSize == 0) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            canvasSize = CANVAS_SCREENS * Math.min(metrics.widthPixels, metrics.heightPixels);
        }
        return canvasSize;
    }

    // Position indicators. The framework draws a scrollbar down the right edge and along the
    // bottom from these three numbers per axis; awakenScrollBars() below fades them in while the
    // canvas is actually moving, so they stay out of the way of the drawing the rest of the time.

    @Override
    public int computeHorizontalScrollRange() { return getCanvasSize(); }

    @Override
    public int computeHorizontalScrollOffset() { return getScrollX(); }

    @Override
    public int computeHorizontalScrollExtent() { return getWidth(); }

    @Override
    public int computeVerticalScrollRange() { return getCanvasSize(); }

    @Override
    public int computeVerticalScrollOffset() { return getScrollY(); }

    @Override
    public int computeVerticalScrollExtent() { return getHeight(); }

    /** The window covers part of the canvas, so the rest of it is how far there is to scroll. */
    private int maxScrollX() { return Math.max(0, getCanvasSize() - getWidth()); }

    private int maxScrollY() { return Math.max(0, getCanvasSize() - getHeight()); }

    private static int clamp(int value, int max) { return Math.max(0, Math.min(value, max)); }

    /**
     * The rectangle every visible stroke fits inside, or null if nothing has been drawn. Eraser
     * strokes are left out: they are white on white, so they can't extend what is actually visible.
     */
    private RectF contentBounds() {
        RectF bounds = null;
        for (WhiteboardText item : texts) {
            if (item.text == null || item.text.isEmpty()) continue;
            RectF box = textBounds(item);
            if (bounds == null) bounds = box; else bounds.union(box);
        }
        for (Stroke stroke : committedStrokes) {
            if (stroke.tool == TOOL_ERASER || stroke.points == null) continue;
            for (PointF point : stroke.points) {
                float radius = stroke.width / 2f;
                if (bounds == null) {
                    bounds = new RectF(point.x - radius, point.y - radius,
                            point.x + radius, point.y + radius);
                } else {
                    bounds.union(point.x - radius, point.y - radius,
                            point.x + radius, point.y + radius);
                }
            }
        }
        return bounds;
    }

    /**
     * @param toolType    from {@link MotionEvent#getToolType(int)} — a stylus flipped to its eraser
     *                    end erases whatever is selected in the rail, which is the whole point of
     *                    turning the pen over.
     * @param buttonState stylus barrel buttons; some pens report the eraser that way instead.
     * @param pressure    scales the stroke's width. Applied once, at the start: a stroke carries a
     *                    single width in the database (points_blob is x/y pairs only), so
     *                    per-point pressure would mean changing the storage format.
     */
    private void startStroke(float x, float y, int toolType, int buttonState, float pressure) {
        int tool = isEraserEnd(toolType, buttonState) ? TOOL_ERASER : currentTool;
        float width = (tool == TOOL_HIGHLIGHTER) ? currentWidth * 4 : currentWidth;
        if (isStylus(toolType)) width *= pressureScale(pressure);

        currentStroke = new Stroke();
        currentStroke.id        = UUID.randomUUID().toString();
        currentStroke.authorId  = localDeviceId;
        currentStroke.tool      = tool;
        currentStroke.color     = (tool == TOOL_ERASER) ? Color.WHITE : currentColor;
        currentStroke.width     = width;
        currentStroke.points    = new ArrayList<>();
        currentStroke.createdAt = System.currentTimeMillis();

        currentStroke.points.add(new PointF(x, y));
        currentPath = new Path();
        currentPath.moveTo(x, y);
    }

    private int touchSlop() {
        return android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
    }

    private static boolean isStylus(int toolType) {
        return toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER;
    }

    /** The eraser end of a pen, however this one reports it. */
    private static boolean isEraserEnd(int toolType, int buttonState) {
        return toolType == MotionEvent.TOOL_TYPE_ERASER
                || (buttonState & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                || (buttonState & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0;
    }

    private static float pressureScale(float pressure) {
        return Math.max(MIN_PRESSURE_SCALE, Math.min(pressure, MAX_PRESSURE_SCALE));
    }

    private void continueStroke(float x, float y) {
        if (currentStroke == null) return;
        PointF last = currentStroke.points.get(currentStroke.points.size() - 1);
        float midX = (last.x + x) / 2f;
        float midY = (last.y + y) / 2f;
        currentPath.quadTo(last.x, last.y, midX, midY);
        currentStroke.points.add(new PointF(x, y));
    }

    private void finishStroke(float x, float y) {
        if (currentStroke == null) return;
        continueStroke(x, y);

        committedStrokes.add(currentStroke);
        strokePaths.put(currentStroke.id, currentPath);

        Stroke completed = currentStroke;
        currentStroke = null;
        currentPath   = new Path();
        invalidate();

        if (strokeListener != null) {
            strokeListener.onStrokeComplete(completed); // Fragment saves it to DB
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // The canvas arrives already offset by the scroll position (the parent applies it before
        // onDraw), so everything is drawn at its canvas coordinates and the window lands correctly.
        drawBackground(canvas, getScrollX(), getScrollY(),
                getScrollX() + getWidth(), getScrollY() + getHeight());
        drawStrokes(canvas);
    }

    /**
     * Fills the given canvas-space rectangle with the board's paper.
     *
     * <p>Dots are placed on a grid in canvas coordinates, not window ones, so they stay put under
     * the drawing while you pan instead of sliding across it. Only the ones inside the rectangle
     * are drawn, so the cost is a screenful however big the canvas is.
     */
    private void drawBackground(Canvas canvas, float left, float top, float right, float bottom) {
        canvas.drawColor(backgroundStyle == BACKGROUND_PAPER ? PAPER_COLOUR : Color.WHITE);
        if (backgroundStyle != BACKGROUND_DOTS) return;

        float density = getResources().getDisplayMetrics().density;
        float spacing = DOT_SPACING_DP * density;
        float radius = DOT_RADIUS_DP * density;
        for (float x = (float) Math.floor(left / spacing) * spacing; x <= right; x += spacing) {
            for (float y = (float) Math.floor(top / spacing) * spacing; y <= bottom; y += spacing) {
                canvas.drawCircle(x, y, radius, dotPaint);
            }
        }
    }

    /**
     * Draws a board's content at its canvas coordinates. Shared by the screen and by Export.
     *
     * <p>Text is drawn under the ink on purpose: a highlighter stroke over a label should read as
     * highlighting it, and an eraser stroke is opaque white, so drawing text last would leave it
     * floating over the marks meant to cover it.
     */
    private void drawStrokes(Canvas canvas) {
        // Ink goes in its own layer so an eraser stroke can clear back to the paper beneath it.
        // Drawing it straight onto the canvas would make CLEAR punch a hole through the paper too.
        int layer = canvas.saveLayer(null, null);
        for (WhiteboardText item : texts) drawText(canvas, item);

        for (Stroke s : committedStrokes) {
            Path path = strokePaths.get(s.id);
            if (path == null) path = buildPathForStroke(s);
            canvas.drawPath(path, getPaintForStroke(s));
        }

        if (currentStroke != null && !currentPath.isEmpty()) {
            canvas.drawPath(currentPath, getPaintForStroke(currentStroke));
        }
        canvas.restoreToCount(layer);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** One text item, laid out at its canvas position. Multi-line text wraps at its own newlines. */
    private void drawText(Canvas canvas, WhiteboardText item) {
        if (item.text == null || item.text.isEmpty()) return;
        textPaint.setColor(item.color);
        textPaint.setTextSize(item.size);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float lineHeight = metrics.descent - metrics.ascent;
        float baseline = item.y - metrics.ascent;
        for (String line : item.text.split("\n", -1)) {
            canvas.drawText(line, item.x, baseline, textPaint);
            baseline += lineHeight;
        }
    }

    /** The box a text item occupies on the canvas — for content bounds, and so export includes it. */
    private RectF textBounds(WhiteboardText item) {
        textPaint.setTextSize(item.size);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float lineHeight = metrics.descent - metrics.ascent;
        String[] lines = item.text == null ? new String[0] : item.text.split("\n", -1);
        float widest = 0f;
        for (String line : lines) widest = Math.max(widest, textPaint.measureText(line));
        return new RectF(item.x, item.y, item.x + widest, item.y + lineHeight * lines.length);
    }

    private Path buildPathForStroke(Stroke stroke) {
        Path path = new Path();
        if (stroke.points == null || stroke.points.isEmpty()) return path;

        path.moveTo(stroke.points.get(0).x, stroke.points.get(0).y);
        for (int i = 1; i < stroke.points.size() - 1; i++) {
            PointF curr = stroke.points.get(i);
            PointF next = stroke.points.get(i + 1);
            path.quadTo(curr.x, curr.y, (curr.x + next.x) / 2f, (curr.y + next.y) / 2f);
        }
        if (stroke.points.size() > 1) {
            PointF last = stroke.points.get(stroke.points.size() - 1);
            path.lineTo(last.x, last.y);
        }
        strokePaths.put(stroke.id, path);
        return path;
    }

    private Paint getPaintForStroke(Stroke stroke) {
        Paint p;
        switch (stroke.tool) {
            case TOOL_ERASER:
                p = eraserPaint;
                break;
            case TOOL_HIGHLIGHTER:
                p = highlighterPaint;
                p.setColor(stroke.color);
                p.setAlpha(80);
                break;
            default:
                p = penPaint;
                p.setColor(stroke.color);
                break;
        }
        p.setStrokeWidth(stroke.width);
        return p;
    }
}
