package mse.quill.ui.splash;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import mse.quill.R;

/**
 * Quill's wordmark — a Caprasimo "Q" followed by two dots — with the dots animating in and out
 * one at a time, forever.
 *
 * <p>This is a custom {@link View} rather than the usual Material widget (see the Material 3
 * convention in memory/note.md): there is no MDC component for a brand mark, and the alternative
 * — a {@code MaterialTextView} plus two circle-shaped {@code View}s — cannot keep the dots'
 * size, spacing and optical centre locked to the glyph the way the mark needs. Drawing the "Q"
 * as text also means the logo stays sharp at any size, which the 157px {@code drawable/logo.png}
 * export does not.
 *
 * <p>Everything is derived from the glyph's measured bounds, so the single {@code
 * android:textSize} attribute scales the whole mark. The ratios come from measuring
 * {@code drawable/logo.png}: dots are 0.30 of the "Q" box tall, sit 0.70 of the way down it, and
 * are separated by roughly a fifteenth of it.
 */
public class QuillLogoView extends View {

    /** Dots in the mark, matching the logo. */
    private static final int DOT_COUNT = 2;

    /** Fade for one dot, in ms. One full cycle is {@code 2 * DOT_COUNT * FADE_MS + 2 * HOLD_MS}. */
    private static final long FADE_MS = 260L;
    /** Pause at the ends of the cycle — all dots showing, then none — in ms. */
    private static final long HOLD_MS = 180L;

    /** Dot geometry as fractions of the "Q" glyph box. See the class doc. */
    private static final float DOT_DIAMETER_RATIO = 0.30f;
    private static final float DOT_CENTER_Y_RATIO = 0.70f;
    private static final float DOT_GAP_RATIO = 0.071f;
    private static final float GLYPH_GAP_RATIO = 0.036f;

    /** Dots grow into place as they fade in rather than just materialising. */
    private static final float DOT_MIN_SCALE = 0.65f;

    private static final Interpolator EASING = new AccelerateDecelerateInterpolator();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect glyphBounds = new Rect();
    private final float[] dotAlphas = new float[DOT_COUNT];

    @Nullable private ValueAnimator animator;

    public QuillLogoView(Context context) {
        this(context, null);
    }

    public QuillLogoView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QuillLogoView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        // Reuses the framework text attrs rather than declaring styleables of its own — the two
        // things this view needs to be told are exactly "how big" and "what colour".
        float textSize = getResources().getDimension(R.dimen.splash_logo_size);
        int color = ContextCompat.getColor(context, R.color.brand_ink);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(
                    attrs, new int[]{android.R.attr.textSize, android.R.attr.textColor});
            textSize = a.getDimension(0, textSize);
            color = a.getColor(1, color);
            a.recycle();
        }

        paint.setTypeface(ResourcesCompat.getFont(context, R.font.caprasimo_regular));
        paint.setTextSize(textSize);
        paint.setColor(color);
        measureGlyph();
    }

    /** Sets the mark's ink colour; the "Q" and the dots are always drawn in it. */
    public void setLogoColor(@ColorInt int color) {
        paint.setColor(color);
        invalidate();
    }

    private void measureGlyph() {
        paint.getTextBounds("Q", 0, 1, glyphBounds);
    }

    private float dotDiameter() {
        return glyphBounds.height() * DOT_DIAMETER_RATIO;
    }

    private float markWidth() {
        float d = dotDiameter();
        return glyphBounds.width()
                + glyphBounds.height() * GLYPH_GAP_RATIO
                + DOT_COUNT * d
                + (DOT_COUNT - 1) * glyphBounds.height() * DOT_GAP_RATIO;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = (int) Math.ceil(markWidth()) + getPaddingLeft() + getPaddingRight();
        int height = glyphBounds.height() + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(
                resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float left = getPaddingLeft();
        float top = getPaddingTop();

        // getTextBounds is relative to the origin at the baseline, so shifting by -left/-top puts
        // the glyph's ink flush against the padding box.
        canvas.drawText("Q", left - glyphBounds.left, top - glyphBounds.top, paint);

        float glyphHeight = glyphBounds.height();
        float diameter = dotDiameter();
        float radius = diameter / 2f;
        float centerY = top + glyphHeight * DOT_CENTER_Y_RATIO;
        float centerX = left + glyphBounds.width() + glyphHeight * GLYPH_GAP_RATIO + radius;

        int fullAlpha = paint.getAlpha();
        for (int i = 0; i < DOT_COUNT; i++) {
            float alpha = dotAlphas[i];
            if (alpha > 0f) {
                paint.setAlpha(Math.round(fullAlpha * alpha));
                float scale = DOT_MIN_SCALE + (1f - DOT_MIN_SCALE) * alpha;
                canvas.drawCircle(centerX, centerY, radius * scale, paint);
                paint.setAlpha(fullAlpha);
            }
            centerX += diameter + glyphHeight * DOT_GAP_RATIO;
        }
    }

    /**
     * Alpha of dot {@code index} at {@code t} ms into the cycle: dots fade in left to right, hold,
     * then fade out right to left, so the mark retracts the way it was drawn.
     */
    private static float alphaAt(int index, long t) {
        long fadeInStart = index * FADE_MS;
        if (t < fadeInStart) return 0f;
        if (t < fadeInStart + FADE_MS) {
            return EASING.getInterpolation((t - fadeInStart) / (float) FADE_MS);
        }
        long fadeOutStart = DOT_COUNT * FADE_MS + HOLD_MS + (DOT_COUNT - 1 - index) * FADE_MS;
        if (t < fadeOutStart) return 1f;
        if (t < fadeOutStart + FADE_MS) {
            return 1f - EASING.getInterpolation((t - fadeOutStart) / (float) FADE_MS);
        }
        return 0f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startDotAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopDotAnimation();
        super.onDetachedFromWindow();
    }

    private void startDotAnimation() {
        if (animator != null) return;
        long cycleMs = 2L * DOT_COUNT * FADE_MS + 2L * HOLD_MS;

        // One animator walking the cycle clock, rather than an AnimatorSet per dot: the dots'
        // alphas are a pure function of that clock, which keeps them from drifting apart over the
        // (unbounded) number of repeats.
        ValueAnimator a = ValueAnimator.ofFloat(0f, cycleMs);
        a.setDuration(cycleMs);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(null); // the per-dot easing in alphaAt does the shaping
        a.addUpdateListener(animation -> {
            long t = (long) (float) (Float) animation.getAnimatedValue();
            for (int i = 0; i < DOT_COUNT; i++) {
                dotAlphas[i] = alphaAt(i, t);
            }
            invalidate();
        });
        a.start();
        animator = a;
    }

    private void stopDotAnimation() {
        if (animator == null) return;
        animator.cancel();
        animator = null;
    }
}
