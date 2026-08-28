package mse.quill.ui.common;

import android.view.HapticFeedbackConstants;
import android.view.View;

import mse.quill.ui.profile.ProfilePreferences;

/**
 * The small taps the app gives back — a card turning over, an answer landing, a row crossing the
 * point where letting go would delete it.
 *
 * <p>Routed through one place so the Profile switch that turns them off actually turns all of them
 * off. A {@code performHapticFeedback} call sprinkled at each site would be one more thing to
 * remember on the day a new gesture is added, and the setting would silently start lying.
 *
 * <p>Constants rather than a {@code Vibrator} and a duration: the platform ones are tuned per
 * device and respect the user's system-level haptic settings, which a hand-rolled buzz does not.
 * Quill's own switch sits on top of that — someone who has left haptics on system-wide may still
 * not want them from a notes app.
 */
public final class Haptics {

    private Haptics() {}

    /** A card turning over, or a panel arriving — something changed and it was asked for. */
    public static void tick(View view) {
        perform(view, HapticFeedbackConstants.CLOCK_TICK);
    }

    /** A choice landing: grading a card, answering a question. Weightier than a tick. */
    public static void confirm(View view) {
        perform(view, HapticFeedbackConstants.CONFIRM);
    }

    /**
     * A gesture crossing the point of no return — a row dragged far enough that letting go deletes
     * it. The one haptic that is genuinely information rather than decoration: it is what tells a
     * thumb the swipe has taken, without asking the eye to judge a distance.
     */
    public static void threshold(View view) {
        perform(view, HapticFeedbackConstants.LONG_PRESS);
    }

    private static void perform(View view, int constant) {
        if (view == null) return;
        if (!ProfilePreferences.hapticsEnabled(view.getContext())) return;
        // FLAG_IGNORE_VIEW_SETTING is deliberately not passed: a view that has opted out of haptics
        // has done so for a reason, and this is not important enough to overrule it.
        view.performHapticFeedback(constant);
    }
}
