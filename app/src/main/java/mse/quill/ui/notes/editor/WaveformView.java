package mse.quill.ui.notes.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/** Lightweight amplitude-bar visualizer for an in-progress recording. Android doesn't expose raw
 *  PCM samples from a MediaRecorder capture without standing up a separate AudioRecord pipeline,
 *  so — like most voice-memo apps — this approximates a waveform by polling
 *  MediaRecorder#getMaxAmplitude() a few times a second and drawing each reading as a scrolling
 *  bar, newest on the right. */
public class WaveformView extends View {

    private static final int MAX_AMPLITUDE = 32767;
    private static final float BAR_WIDTH_DP = 3f;
    private static final float BAR_GAP_DP = 3f;
    private static final float MIN_BAR_HEIGHT_DP = 3f;

    private final Deque<Float> levels = new ArrayDeque<>();
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int maxBars = 0;

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        barPaint.setColor(context.getColor(mse.quill.R.color.brand_purple));
        barPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public WaveformView(Context context) {
        this(context, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float density = getResources().getDisplayMetrics().density;
        float step = (BAR_WIDTH_DP + BAR_GAP_DP) * density;
        maxBars = Math.max(1, (int) (w / step));
        while (levels.size() > maxBars) levels.removeFirst();
    }

    /** Pushes the latest MediaRecorder#getMaxAmplitude() reading (0..32767). */
    public void addAmplitude(int amplitude) {
        // Square-root scaling: raw amplitude is heavily weighted toward low values for normal
        // speech volume, which reads as a nearly flat line — this keeps everyday talking visible
        // while still letting loud peaks stand out.
        float normalized = (float) Math.sqrt(Math.min(1f, amplitude / (float) MAX_AMPLITUDE));
        levels.addLast(normalized);
        while (levels.size() > maxBars) levels.removeFirst();
        invalidate();
    }

    public void clear() {
        levels.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (levels.isEmpty()) return;

        float density = getResources().getDisplayMetrics().density;
        float barWidth = BAR_WIDTH_DP * density;
        float step = barWidth + BAR_GAP_DP * density;
        float minHalfHeight = MIN_BAR_HEIGHT_DP * density;
        float centerY = getHeight() / 2f;
        float maxHalfHeight = getHeight() / 2f;

        barPaint.setStrokeWidth(barWidth);

        float x = getWidth() - barWidth / 2f;
        Iterator<Float> it = levels.descendingIterator();
        while (it.hasNext() && x >= 0) {
            float level = it.next();
            float halfHeight = Math.max(minHalfHeight, level * maxHalfHeight);
            canvas.drawLine(x, centerY - halfHeight, x, centerY + halfHeight, barPaint);
            x -= step;
        }
    }
}
