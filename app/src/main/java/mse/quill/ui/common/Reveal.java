package mse.quill.ui.common;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import mse.quill.R;

/**
 * The way things arrive in Quill: a short rise into place, or a pop for the one thing on screen
 * worth pointing at.
 *
 * <p>Two shapes, used everywhere something appears rather than having always been there — the
 * welcome screen introducing itself, the report of what the sample content created, the panel at
 * the end of a review. Written once so those moments share a timing and read as the same app; a
 * screen that invents its own duration is how an app ends up feeling assembled from parts.
 *
 * <p><b>Rise, not slide.</b> Content comes up a few pixels and fades in together. The distance is
 * deliberately small — {@code spacing_md} rather than a dramatic sweep — because this is a full
 * stop being placed, not a transition between screens, and a long travel makes the user wait for
 * information they can already read.
 *
 * <p><b>Animators off means off.</b> Every entrance here checks
 * {@link ValueAnimator#areAnimatorsEnabled()} and, when the user has turned animations off at the
 * system level or is on a battery saver that has, snaps straight to the final state. Skipping that
 * check is how a "remove animations" setting turns into content that never appears at all, since
 * these all start from {@code alpha = 0}.
 */
public final class Reveal {

    private Reveal() {}

    /** Between one staggered item and the next. Long enough to read as a sequence, short enough
     *  that a five-item list is still over in well under half a second. */
    public static final long STAGGER_MS = 60;

    private static final long RISE_MS = 220;
    private static final long POP_MS = 320;

    /**
     * Fades a view in as it rises the last {@code spacing_md} into place.
     *
     * @param startDelay milliseconds to wait first — see {@link #stagger} for running a list.
     */
    public static void riseIn(View view, long startDelay) {
        if (view == null) return;
        if (!animationsOn()) {
            settle(view);
            return;
        }
        view.setAlpha(0f);
        view.setTranslationY(view.getResources().getDimension(R.dimen.spacing_md));
        view.animate().alpha(1f).translationY(0f)
                .setStartDelay(startDelay)
                .setDuration(RISE_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /**
     * Springs a view in from slightly too small — for the single element a screen is actually
     * about, never for a list. The overshoot is what makes it read as arriving rather than as
     * fading up, and a row of things all overshooting at once looks like a fault.
     */
    public static void popIn(View view, long startDelay) {
        if (view == null) return;
        if (!animationsOn()) {
            settle(view);
            return;
        }
        view.setAlpha(0f);
        view.setScaleX(0.6f);
        view.setScaleY(0.6f);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(startDelay)
                .setDuration(POP_MS)
                .setInterpolator(new OvershootInterpolator(2f))
                .start();
    }

    /**
     * Rises a list in one item after another.
     *
     * @return when the last item lands, so a caller can carry on after the sequence rather than
     *         guessing at a total.
     */
    public static long stagger(long firstDelay, View... views) {
        long delay = firstDelay;
        for (View view : views) {
            riseIn(view, delay);
            delay += STAGGER_MS;
        }
        return delay;
    }

    /** Every child of a container, in the order they were added. */
    public static long staggerChildren(android.view.ViewGroup parent, long firstDelay) {
        long delay = firstDelay;
        for (int i = 0; i < parent.getChildCount(); i++) {
            riseIn(parent.getChildAt(i), delay);
            delay += STAGGER_MS;
        }
        return delay;
    }

    private static boolean animationsOn() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    /** The state every entrance above ends in — also the state it starts in when animations are
     *  off, since these all begin invisible. */
    private static void settle(View view) {
        view.setAlpha(1f);
        view.setTranslationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
    }
}
