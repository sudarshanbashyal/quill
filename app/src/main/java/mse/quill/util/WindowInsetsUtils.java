package mse.quill.util;

import android.view.View;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Puts the status-bar inset where a screen wants it, so the bar takes on the colour of whatever is
 * underneath it rather than a band of window background.
 *
 * <p>{@code MainActivity} deliberately doesn't pad its root by the top inset. If it did, every
 * screen would start below the status bar and the strip behind the clock would show the window
 * background — white — no matter what the screen itself looks like. Instead the activity applies
 * this to each screen as its view is created, so no screen has to remember to ask; see
 * {@link TopInsetHost} for the two that need it somewhere other than their root.
 */
public final class WindowInsetsUtils {

    private WindowInsetsUtils() {}

    /**
     * Implemented by a fragment whose root is <em>not</em> the view that should run up behind the
     * status bar — a transparent root with a coloured header inside it, or a root whose insets
     * listener is already spoken for. Screens that don't implement this get the inset on their
     * root, which is what a screen carrying its own background wants.
     */
    public interface TopInsetHost {
        /** The view to pad; {@code root} is the fragment's own view. */
        View topInsetTarget(View root);
    }

    /**
     * Adds the status-bar inset to {@code view}'s top padding, keeping whatever padding the layout
     * gave it. The original values are captured once, so re-dispatched insets (rotation, a resized
     * window) don't compound.
     *
     * <p>Any {@code minHeight} grows by the inset too. Without that, a view shorter than its
     * minimum keeps the same total height and the extra padding pushes its contents *down* inside
     * it instead of moving the view down — which on Home slid the greeting's subtitle under the
     * content sheet that overlaps the header's bottom edge.
     */
    public static void applyTopInset(View view) {
        int layoutPaddingTop = view.getPaddingTop();
        int layoutMinHeight = view.getMinimumHeight();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int statusBar = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            v.setPadding(v.getPaddingLeft(), layoutPaddingTop + statusBar,
                    v.getPaddingRight(), v.getPaddingBottom());
            v.setMinimumHeight(layoutMinHeight + statusBar);
            return insets;
        });
        // The listener only fires on the next dispatch, which has usually already happened by the
        // time a fragment's view is created.
        ViewCompat.requestApplyInsets(view);
    }
}
