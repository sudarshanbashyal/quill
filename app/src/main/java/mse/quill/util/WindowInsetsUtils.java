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
        /**
         * The view to pad; {@code root} is the fragment's own view. Return null to take nothing —
         * for a screen that already handles the inset itself, such as one built on an
         * {@code AppBarLayout}, which offsets its own children and would otherwise be padded twice.
         */
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
        if (view == null) return;
        apply(view, true);
    }

    /**
     * The same, for chrome that sits <em>above</em> the screens — the now-playing bar. It always
     * takes the inset, and while it is on screen the screens below it take none: see
     * {@link #setChromeOwnsTopInset}.
     */
    public static void applyChromeTopInset(View view) {
        apply(view, false);
    }

    /**
     * Moves the status-bar inset between the chrome bar and the screens as the bar comes and goes.
     *
     * <p>Only one of them can pay for it. Whichever is topmost has to run up behind the status bar;
     * the other must not add a second gap of the same height. Changing this re-dispatches insets
     * from the activity's root, which is what re-runs every listener registered above.
     */
    public static void setChromeOwnsTopInset(View activityRoot, boolean owns) {
        if (chromeOwnsTopInset == owns) return;
        chromeOwnsTopInset = owns;
        ViewCompat.requestApplyInsets(activityRoot);
    }

    private static boolean chromeOwnsTopInset = false;

    private static void apply(View view, boolean yieldsToChrome) {
        int layoutPaddingTop = view.getPaddingTop();
        int layoutMinHeight = view.getMinimumHeight();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int statusBar = yieldsToChrome && chromeOwnsTopInset
                    ? 0 : insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
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
