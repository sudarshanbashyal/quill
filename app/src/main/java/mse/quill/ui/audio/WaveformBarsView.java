package mse.quill.ui.audio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.core.content.ContextCompat;

import mse.quill.R;
import mse.quill.audio.WaveformCache;

/**
 * A recorded clip drawn as bars, with the part already played picked out in the brand colour.
 *
 * <p>Distinct from {@code WaveformView}, which scrolls live amplitudes while recording and has no
 * notion of a whole clip or a position in it. This one is handed the finished shape of a file and
 * draws all of it at once, which is what makes it a scrubber: any x maps to a time.
 */
public class WaveformBarsView extends View {

    private static final float BAR_WIDTH_DP = 2.5f;
    private static final float BAR_GAP_DP = 2f;
    private static final float MIN_BAR_HEIGHT_DP = 2f;
    /** Height of the flat line shown before the file has been decoded, as a fraction of the view.
     *  Deliberately low: it reads as "not measured yet", not as a very quiet recording. */
    private static final float PLACEHOLDER_LEVEL = 0.12f;

    public interface SeekListener {
        /** @param fraction 0..1 of the clip's length. */
        void onSeekRequested(float fraction);
    }

    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unplayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float[] source;
    private float[] bars = new float[0];
    private float progress = 0f;
    private SeekListener seekListener;

    /** Whether this waveform is a live scrubber right now — see {@link #setScrubbable}. */
    private boolean scrubbable = true;
    /** Dormant-clip gesture state: set once a drag has passed the slop and become a scrub. */
    private boolean dragScrubbing;
    /** Set when a long press has claimed the gesture, so the finger lifting doesn't also seek. */
    private boolean longPressTaken;
    private float downX;
    private float downY;
    private final Runnable longPressRunnable = new Runnable() {
        @Override public void run() {
            longPressTaken = true;
            // The card owns this gesture. Delegating rather than handling it here keeps the delete
            // confirmation in one place — the segment's own long-click listener.
            if (getParent() instanceof View) ((View) getParent()).performLongClick();
        }
    };

    public WaveformBarsView(Context context) {
        this(context, null);
    }

    public WaveformBarsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        playedPaint.setColor(ContextCompat.getColor(context, R.color.brand_purple));
        unplayedPaint.setColor(ContextCompat.getColor(context, R.color.waveform_unplayed));
        playedPaint.setStrokeCap(Paint.Cap.ROUND);
        unplayedPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** @param waveform decoded bar heights, or null while they are still being measured. */
    public void setWaveform(float[] waveform) {
        this.source = waveform;
        resampleToWidth();
        invalidate();
    }

    /** @param fraction how far through the clip the playhead is, 0..1. */
    public void setProgress(float fraction) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        if (clamped == progress) return;
        progress = clamped;
        invalidate();
    }

    /** Setting a listener is what makes the waveform scrubabble; without one it is just a picture
     *  and taps fall through to whatever is underneath. */
    public void setSeekListener(SeekListener listener) {
        this.seekListener = listener;
    }

    /**
     * Whether this waveform is the playhead of a loaded clip, or just the shape of a dormant one.
     *
     * <p>It decides who owns a press. A live clip's waveform is a scrubber, and seeking the instant
     * the finger lands is the whole point of it. A dormant one is a picture on a card the user may
     * well be reaching for in order to <em>delete</em> — so a press there waits long enough to tell
     * a tap from a hold, and a hold goes to the card's long-click listener instead of seeking.
     * Without this, the card's long press was unreachable anywhere over the waveform: the touch was
     * consumed here and, worse, seeking a dormant clip starts it, so holding to delete began
     * playing the recording at whatever point the finger landed.
     */
    public void setScrubbable(boolean scrubbable) {
        if (this.scrubbable == scrubbable) return;
        this.scrubbable = scrubbable;
        cancelPendingLongPress();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resampleToWidth();
    }

    private void resampleToWidth() {
        int width = getWidth();
        if (width <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        float step = (BAR_WIDTH_DP + BAR_GAP_DP) * density;
        int count = Math.max(1, (int) (width / step));
        bars = source == null ? flat(count) : WaveformCache.resample(source, count);
    }

    private static float[] flat(int count) {
        float[] placeholder = new float[count];
        for (int i = 0; i < count; i++) placeholder[i] = PLACEHOLDER_LEVEL;
        return placeholder;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (seekListener == null) return super.onTouchEvent(event);
        if (!scrubbable) return onDormantTouchEvent(event);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // The scrubber lives inside a scrolling note, which will happily steal the
                // gesture the moment a drag drifts vertically. Claim it up front.
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE: {
                float fraction = event.getX() / Math.max(1, getWidth());
                setProgress(fraction);
                seekListener.onSeekRequested(Math.max(0f, Math.min(1f, fraction)));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /**
     * A press on a clip that isn't loaded, where a hold means "delete this recording".
     *
     * <p>Nothing happens on the way down — the seek waits for the finger to lift, because starting
     * the clip immediately is exactly the behaviour that made the card's long press unreachable.
     * A drag past the touch slop is still a scrub and claims the gesture from the scrolling note at
     * that point, not before, so a vertical swipe over a recording still scrolls the note.
     */
    private boolean onDormantTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragScrubbing = false;
                longPressTaken = false;
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (longPressTaken) return true;
                if (!dragScrubbing) {
                    float dx = Math.abs(event.getX() - downX);
                    float dy = Math.abs(event.getY() - downY);
                    float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (dx < slop && dy < slop) return true; // still a candidate long press

                    if (dy > dx) {
                        // Reading down the note, not scrubbing across the clip. Let go entirely:
                        // the scrolling parent is left free to intercept, which it does on the
                        // next move, and this gesture comes back as ACTION_CANCEL.
                        cancelPendingLongPress();
                        return false;
                    }
                    // Committed to a scrub: no longer a candidate long press, and the note must
                    // stop trying to scroll underneath it.
                    dragScrubbing = true;
                    cancelPendingLongPress();
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                seekTo(event.getX());
                return true;
            }

            case MotionEvent.ACTION_UP:
                cancelPendingLongPress();
                getParent().requestDisallowInterceptTouchEvent(false);
                // A hold already did something else, and a drag has been seeking all along.
                if (!longPressTaken && !dragScrubbing) seekTo(event.getX());
                return true;

            case MotionEvent.ACTION_CANCEL:
                cancelPendingLongPress();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private void seekTo(float x) {
        float fraction = x / Math.max(1, getWidth());
        setProgress(fraction);
        seekListener.onSeekRequested(Math.max(0f, Math.min(1f, fraction)));
    }

    private void cancelPendingLongPress() {
        removeCallbacks(longPressRunnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        // A segment can be recycled out from under a finger that is still down.
        cancelPendingLongPress();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bars.length == 0) return;

        float density = getResources().getDisplayMetrics().density;
        float barWidth = BAR_WIDTH_DP * density;
        float step = barWidth + BAR_GAP_DP * density;
        float minHalfHeight = MIN_BAR_HEIGHT_DP * density / 2f;
        float centerY = getHeight() / 2f;
        float maxHalfHeight = getHeight() / 2f - minHalfHeight;

        playedPaint.setStrokeWidth(barWidth);
        unplayedPaint.setStrokeWidth(barWidth);

        float playedUpTo = progress * bars.length;
        float x = barWidth / 2f;
        for (int i = 0; i < bars.length; i++) {
            float halfHeight = minHalfHeight + bars[i] * maxHalfHeight;
            Paint paint = i < playedUpTo ? playedPaint : unplayedPaint;
            canvas.drawLine(x, centerY - halfHeight, x, centerY + halfHeight, paint);
            x += step;
        }
    }
}
