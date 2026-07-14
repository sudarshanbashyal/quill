package mse.quill.ui.whiteboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import mse.quill.model.Stroke;

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
 * integration with our Stroke/StrokeDao model without pulling in an external
 * drawing library.
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

    // ── State ───────────────────────────────────────────────────────────────
    private final List<Stroke>      committedStrokes = new ArrayList<>();
    private final Map<String, Path> strokePaths      = new HashMap<>();
    private       Stroke            currentStroke;
    private       Path              currentPath      = new Path();

    private int    currentTool  = TOOL_PEN;
    private int    currentColor = Color.BLACK;
    private float  currentWidth = 6f;
    private String localDeviceId = "local-user"; // single-device placeholder

    private final Paint penPaint         = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eraserPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlighterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public interface StrokeListener {
        void onStrokeComplete(Stroke stroke);
    }
    private StrokeListener strokeListener;

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
    }

    // ── Public API used by WhiteboardFragment ──────────────────────────────────

    public void setStrokeListener(StrokeListener listener) {
        this.strokeListener = listener;
    }

    public void setTool(int tool)          { this.currentTool = tool; }
    public void setColor(int color)        { this.currentColor = color; }
    public void setStrokeWidth(float width){ this.currentWidth = width; }

    /** Load previously saved strokes from the database when the screen opens. */
    public void loadStrokes(List<Stroke> strokes) {
        committedStrokes.clear();
        strokePaths.clear();
        for (Stroke s : strokes) {
            committedStrokes.add(s);
            buildPathForStroke(s);
        }
        invalidate(); // triggers onDraw()
    }

    /** Remove one stroke (used by Undo). */
    public void removeStroke(String strokeId) {
        committedStrokes.removeIf(s -> s.id.equals(strokeId));
        strokePaths.remove(strokeId);
        invalidate();
    }

    /** Wipe the canvas (used by Clear button). */
    public void clearAll() {
        committedStrokes.clear();
        strokePaths.clear();
        currentPath.reset();
        invalidate();
    }

    /** Render the current canvas content into a Bitmap (used by Export). */
    public Bitmap exportToBitmap() {
        Bitmap bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        draw(canvas);
        return bitmap;
    }

    // ── Touch handling ────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startStroke(x, y);
                return true;

            case MotionEvent.ACTION_MOVE:
                // Historical points give smoother lines on fast movement
                int historySize = event.getHistorySize();
                for (int i = 0; i < historySize; i++) {
                    continueStroke(event.getHistoricalX(i), event.getHistoricalY(i));
                }
                continueStroke(x, y);
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                finishStroke(x, y);
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void startStroke(float x, float y) {
        currentStroke = new Stroke();
        currentStroke.id        = UUID.randomUUID().toString();
        currentStroke.authorId  = localDeviceId;
        currentStroke.tool      = currentTool;
        currentStroke.color     = (currentTool == TOOL_ERASER) ? Color.WHITE : currentColor;
        currentStroke.width     = (currentTool == TOOL_HIGHLIGHTER) ? currentWidth * 4 : currentWidth;
        currentStroke.points    = new ArrayList<>();
        currentStroke.createdAt = System.currentTimeMillis();

        currentStroke.points.add(new PointF(x, y));
        currentPath = new Path();
        currentPath.moveTo(x, y);
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

        for (Stroke s : committedStrokes) {
            Path path = strokePaths.get(s.id);
            if (path == null) path = buildPathForStroke(s);
            canvas.drawPath(path, getPaintForStroke(s));
        }

        if (currentStroke != null && !currentPath.isEmpty()) {
            canvas.drawPath(currentPath, getPaintForStroke(currentStroke));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
