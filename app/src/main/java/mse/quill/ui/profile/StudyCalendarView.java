package mse.quill.ui.profile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.time.LocalDate;

import mse.quill.R;
import mse.quill.data.StudyHistory;

/**
 * The study calendar: one small square per day for the last {@link StudyHistory#WEEKS} weeks, a
 * column per week, shaded by how much was done that day.
 *
 * <p>Drawn rather than composed. A grid of this shape is a few hundred cells, and building it from
 * views would mean a few hundred objects, each with layout params and a background drawable, to
 * render something with no state and no interaction. Material has no component for a heatmap either
 * — this is the same kind of deliberate exception to the project's "use the MDC widget" rule as the
 * colour-swatch picker, and for the same reason: there is nothing to reach for.
 *
 * <p>Four shades rather than a continuous ramp. The question the grid answers is "did I study, and
 * roughly how much", and a smooth gradient invites comparisons between days that the underlying
 * number cannot really support — a card's {@code last_reviewed_at} is overwritten each time it is
 * reviewed, so a day's count is a floor, not a total.
 */
public class StudyCalendarView extends View {

    private static final int DAYS_PER_WEEK = 7;

    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] shades = new int[4];
    private final float cellCorner;
    private final float cellGap;

    private StudyHistory history;
    /** The most recent day drawn — the bottom-right cell. */
    private LocalDate lastDay = LocalDate.now();

    public StudyCalendarView(Context context) {
        this(context, null);
    }

    public StudyCalendarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        shades[0] = ContextCompat.getColor(context, R.color.study_cell_empty);
        shades[1] = ContextCompat.getColor(context, R.color.study_cell_light);
        shades[2] = ContextCompat.getColor(context, R.color.study_cell_medium);
        shades[3] = ContextCompat.getColor(context, R.color.study_cell_strong);
        cellCorner = getResources().getDimension(R.dimen.study_cell_corner);
        cellGap = getResources().getDimension(R.dimen.study_cell_gap);
    }

    public void setHistory(StudyHistory history) {
        this.history = history;
        this.lastDay = LocalDate.now();
        invalidate();
    }

    /**
     * Height follows width: the grid is always seven rows, so the cell size is whatever the
     * available width divided by the number of weeks allows, and the height is seven of those.
     * Measuring the other way round would make the grid's size depend on a height nothing has
     * decided yet.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        float cell = cellSize(width);
        int height = Math.round(cell * DAYS_PER_WEEK);
        setMeasuredDimension(width, height);
    }

    private float cellSize(int width) {
        return width / (float) StudyHistory.WEEKS;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cell = cellSize(getWidth());

        // The grid is laid out backwards from today, so the most recent day is always the last
        // cell drawn rather than the calendar drifting as the weeks turn over.
        int totalDays = StudyHistory.WEEKS * DAYS_PER_WEEK;
        for (int i = 0; i < totalDays; i++) {
            LocalDate day = lastDay.minusDays(totalDays - 1L - i);
            int column = i / DAYS_PER_WEEK;
            int row = i % DAYS_PER_WEEK;

            cellPaint.setColor(shadeFor(day));
            float left = column * cell;
            float top = row * cell;
            canvas.drawRoundRect(left + cellGap, top + cellGap,
                    left + cell - cellGap, top + cell - cellGap,
                    cellCorner, cellCorner, cellPaint);
        }
    }

    /**
     * Which of the four shades a day gets.
     *
     * <p>Scaled against the busiest day in the window rather than a fixed number of cards, so the
     * grid reads the same for someone reviewing five cards a day as for someone reviewing eighty.
     * A fixed scale would leave the first user's calendar uniformly pale and tell them nothing.
     */
    private int shadeFor(LocalDate day) {
        if (history == null) return shades[0];
        int count = history.countOn(day);
        if (count <= 0) return shades[0];
        if (history.busiestDay <= 1) return shades[3];

        float share = count / (float) history.busiestDay;
        if (share > 0.66f) return shades[3];
        if (share > 0.33f) return shades[2];
        return shades[1];
    }
}
