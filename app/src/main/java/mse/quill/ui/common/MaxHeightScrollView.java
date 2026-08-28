package mse.quill.ui.common;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * A {@link ScrollView} that is as tall as its content until a ceiling, then scrolls.
 *
 * <p>Exists because a dialog can't say that in layout params. Giving the scroll view a fixed height
 * is the usual workaround and it is wrong in the common case: a picker with three items reserves
 * the same slab of screen as one with fifty, so a short list sits in a tall dialog padded out with
 * empty space. Setting {@code WRAP_CONTENT} instead fixes the short list and breaks the long one,
 * which then grows past the screen and pushes the dialog's buttons off it.
 *
 * <p>Measuring {@code AT_MOST} against the ceiling is what gives both: {@code ScrollView} sizes to
 * its content when the content fits, and clamps when it doesn't.
 */
public class MaxHeightScrollView extends ScrollView {

    private int maxHeight = Integer.MAX_VALUE;

    public MaxHeightScrollView(Context context) {
        super(context);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** @param maxHeight ceiling in pixels; the view is free to be shorter. */
    public void setMaxHeight(int maxHeight) {
        this.maxHeight = maxHeight;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // AT_MOST, never EXACTLY: EXACTLY is the fixed height this class exists to avoid.
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
    }
}
