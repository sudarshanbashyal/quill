package mse.quill.ui.notes;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.OneShotPreDrawListener;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.EnumMap;
import java.util.Map;

import mse.quill.R;
import mse.quill.ui.notes.editor.FormattingButtonView;
import mse.quill.ui.notes.editor.FormattingToolbarController;

/**
 * The "this note can't make flashcards / a quiz yet" explanation, shown as a dialog with a picture
 * of the control the user is being asked to use.
 *
 * <p>It replaces a Snackbar that said the rule and nothing else. The rule was never the hard part:
 * a message about "Q&amp;A blocks with both halves filled in" only helps someone who already knows
 * that a Q&amp;A block is a thing you insert, and where from. The control that inserts one lives in
 * the formatting bar, which is <em>not on screen</em> at the moment the message appears — the bar
 * only exists while the keyboard is up, and both of these actions are reached from the note's
 * options menu with the keyboard down. So a Snackbar pointed at nothing, for a few seconds, and
 * then took itself away.
 *
 * <p>The dialog draws the bar instead, and zooms into the Q&amp;A item so the icon the user has to
 * find is unmistakable. The strip is built from {@link FormattingToolbarController.Item} — the same
 * list the real toolbar is built from — rather than being a screenshot: a screenshot would be one
 * more asset to re-cut whenever the bar changes, would sit at the wrong density, and would be
 * frozen at whatever the bar looked like the day it was taken. This one cannot go stale.
 *
 * <p>The positive button just inserts the block, because a user who has read this far has already
 * said what they want; making them dismiss the dialog and go hunting for the icon they were just
 * shown would be a strange thing to insist on. The picture is still the point — it is what teaches
 * them to do it themselves next time.
 *
 * <p>The Flashcards and Quizzes tabs raise the same dialog when their "+" has nothing to offer,
 * and there it teaches only: no note is open, so there is nothing to add a block to and offering
 * it would be a lie. Same picture either way — the thing being explained is identical, and showing
 * it in one place and a sentence about it in the other would be two answers to one question.
 */
public final class QaBlockHintDialog {

    /** How far the strip is blown up at the end of the zoom. */
    private static final float MAX_ZOOM = 1.9f;
    /** What the items either side fade to, so the zoomed one is the only thing still reading. */
    private static final float DIMMED_ALPHA = 0.2f;

    private static final long CYCLE_MS = 1800L;
    /** Fractions of one direction of the cycle: rest wide, then ramp, then hold zoomed. The rest is
     *  what makes the shot legible — the whole bar has to be seen before the zoom means anything. */
    private static final float REST_FRACTION = 0.2f;
    private static final float RAMP_FRACTION = 0.35f;

    private QaBlockHintDialog() {}

    /** No Q&amp;A blocks at all, and flashcards were asked for. */
    public static void showForFlashcards(Context context, Runnable onAddBlock) {
        show(context,
                context.getString(R.string.qa_hint_flashcards_title),
                context.getString(R.string.qa_hint_flashcards_message),
                null,
                onAddBlock);
    }

    /**
     * Some Q&amp;A blocks, but fewer than a quiz can be built from. {@code usableBlocks} is counted
     * rather than described — "you have 3 of 5" is a different piece of information from "not
     * enough", and it is the one that tells the user how much work is left.
     */
    public static void showForQuiz(Context context, int usableBlocks, int requiredBlocks,
                                   Runnable onAddBlock) {
        show(context,
                context.getString(R.string.qa_hint_quiz_title, requiredBlocks),
                context.getString(R.string.qa_hint_quiz_message),
                context.getString(R.string.qa_hint_quiz_progress_format, usableBlocks, requiredBlocks),
                onAddBlock);
    }

    /**
     * Nothing anywhere in the app can make a deck yet — the Flashcards tab's "+" with no candidate
     * notes behind it.
     */
    public static void showForNoQaAnywhere(Context context) {
        show(context,
                context.getString(R.string.qa_hint_flashcards_title),
                context.getString(R.string.qa_hint_no_notes_message),
                null,
                null);
    }

    /**
     * No note in the app can be made into a quiz — the Quizzes tab's "+" with nothing behind it.
     *
     * <p>Titled and worded as a quiz throughout, rather than borrowing the flashcards copy for the
     * "no blocks at all" half. The two tabs are asking for the same thing to be written, but the
     * reason differs, and a dialog on the Quizzes tab announcing that flashcards come from Q&amp;A
     * blocks is answering a question nobody asked there.
     *
     * @param noneAtAll no note has a usable block at all, as opposed to none having enough of them
     */
    public static void showForNoQuizAnywhere(Context context, int requiredBlocks, boolean noneAtAll) {
        show(context,
                context.getString(R.string.qa_hint_quiz_title, requiredBlocks),
                context.getString(noneAtAll
                        ? R.string.qa_hint_quiz_none_message
                        : R.string.qa_hint_quiz_not_enough_message),
                null,
                null);
    }

    /** {@code onAddBlock} null means there is no note to add to, so the dialog only teaches. */
    private static void show(Context context, String title, String message, String progress,
                             Runnable onAddBlock) {
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_qa_block_hint, null);
        ((TextView) content.findViewById(R.id.qa_hint_message)).setText(message);
        if (progress != null) {
            TextView progressLine = content.findViewById(R.id.qa_hint_progress);
            progressLine.setText(progress);
            progressLine.setVisibility(View.VISIBLE);
        }

        Zoom zoom = buildStrip(content.findViewById(R.id.qa_hint_strip));
        content.findViewById(R.id.qa_hint_strip_card)
                .setContentDescription(context.getString(R.string.qa_hint_strip_description));

        // Deferred to the dismiss, not run from the button. Inserting a block focuses its question
        // field and asks for the keyboard, and showSoftInput is dropped on the floor while a
        // window that isn't the editor's still holds focus — which the dialog does for as long as
        // its click listener is running. The block appeared with no keyboard behind it, so the
        // toolbar the dialog had just spent its whole life pointing at stayed hidden.
        boolean[] addRequested = {false};

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(content);
        if (onAddBlock == null) {
            // One button, and it promises nothing: there is no note under this dialog for a block
            // to land in.
            builder.setPositiveButton(R.string.qa_hint_action_got_it, null);
        } else {
            builder.setPositiveButton(R.string.qa_hint_action_add, (d, which) -> addRequested[0] = true)
                    .setNegativeButton(R.string.qa_hint_action_dismiss, null);
        }
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> zoom.start());
        dialog.setOnDismissListener(d -> {
            zoom.stop();
            if (addRequested[0] && onAddBlock != null) onAddBlock.run();
        });
        dialog.show();
    }

    /**
     * Fills the empty strip with a non-interactive copy of every toolbar item, and returns the
     * animation that zooms in on the Q&amp;A one.
     *
     * <p>The Q&amp;A item gets a slot of its own so a pill can sit behind it. Every other item is
     * added exactly as the real bar adds them — equal weights — so the replica's spacing is the
     * spacing the user will actually see, and the icon's position in the row is a real clue about
     * where to look rather than a rearranged one.
     */
    private static Zoom buildStrip(LinearLayout strip) {
        Context context = strip.getContext();
        strip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        Map<FormattingToolbarController.Item, View> slots =
                new EnumMap<>(FormattingToolbarController.Item.class);
        View qaSlot = null;

        for (FormattingToolbarController.Item item : FormattingToolbarController.Item.values()) {
            FormattingButtonView button = item.newButton(context);
            // A picture of a button, not a button: leaving it clickable would invite a tap that
            // does nothing, in a dialog whose entire subject is what tapping it does.
            button.setClickable(false);
            button.setFocusable(false);
            button.setForeground(null);

            View slot = button;
            if (item == FormattingToolbarController.Item.QA_BLOCK) {
                button.setHighlighted(true);
                slot = wrapWithHighlight(context, button);
                qaSlot = slot;
            }
            strip.addView(slot, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            slots.put(item, slot);
        }

        return new Zoom(strip, qaSlot, slots);
    }

    /**
     * The pill behind the highlighted icon — a MaterialCardView with a fully rounded corner, so the
     * marker is a real M3 surface rather than a shape drawn for this one dialog.
     *
     * <p>It is a ring, not just a tint. The strip's surface and the accent's own container tone are
     * a few percent apart, and against a dialog that is itself faintly purple a filled pill simply
     * disappears — on the device it read as a smudge behind the icon rather than as a marker. The
     * outline is what makes it unambiguously a thing drawn around one control.
     */
    private static View wrapWithHighlight(Context context, FormattingButtonView button) {
        float size = context.getResources().getDimension(R.dimen.qa_hint_highlight_size);

        MaterialCardView pill = new MaterialCardView(context);
        pill.setCardBackgroundColor(ContextCompat.getColor(context, R.color.brand_purple_light));
        pill.setRadius(size / 2f);
        pill.setCardElevation(0f);
        pill.setStrokeColor(ContextCompat.getColor(context, R.color.brand_purple));
        pill.setStrokeWidth(context.getResources()
                .getDimensionPixelSize(R.dimen.qa_hint_highlight_stroke));
        FrameLayout.LayoutParams pillParams =
                new FrameLayout.LayoutParams((int) size, (int) size, android.view.Gravity.CENTER);

        FrameLayout slot = new FrameLayout(context);
        slot.addView(pill, pillParams);
        slot.addView(button, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return slot;
    }

    /**
     * Scales the whole strip about the Q&amp;A slot's centre while fading the rest of the row out.
     *
     * <p>Scaling the strip rather than the one item is what makes it read as a camera move: the
     * neighbouring icons slide outwards and off the card's edges, which is what a zoom looks like,
     * where growing a single icon in place would just look like a button swelling. The pivot has to
     * wait for layout — the items are weighted, so nothing knows where the Q&amp;A slot's centre is
     * until the strip has been measured.
     */
    private static final class Zoom {
        private final LinearLayout strip;
        private final View qaSlot;
        private final Map<FormattingToolbarController.Item, View> slots;
        private ValueAnimator animator;

        Zoom(LinearLayout strip, View qaSlot, Map<FormattingToolbarController.Item, View> slots) {
            this.strip = strip;
            this.qaSlot = qaSlot;
            this.slots = slots;
        }

        void start() {
            OneShotPreDrawListener.add(strip, () -> {
                strip.setPivotX(qaSlot.getLeft() + qaSlot.getWidth() / 2f);
                strip.setPivotY(strip.getHeight() / 2f);

                // With animations turned off system-wide, a repeating animator would either never
                // advance or spin through its cycles unseen. Show the end of the move instead —
                // the zoomed-in frame is the one carrying the message.
                if (!ValueAnimator.areAnimatorsEnabled()) {
                    apply(1f);
                    return;
                }

                animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(CYCLE_MS);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.setRepeatMode(ValueAnimator.REVERSE);
                // The easing lives in zoomFor() rather than in an interpolator, because the shape
                // wanted here is a hold at each end and interpolators can't pause.
                animator.setInterpolator(new LinearInterpolator());
                animator.addUpdateListener(a -> apply(zoomFor((float) a.getAnimatedValue())));
                animator.start();
            });
        }

        void stop() {
            if (animator != null) animator.cancel();
        }

        private void apply(float zoom) {
            float scale = 1f + (MAX_ZOOM - 1f) * zoom;
            strip.setScaleX(scale);
            strip.setScaleY(scale);

            float othersAlpha = 1f - (1f - DIMMED_ALPHA) * zoom;
            for (Map.Entry<FormattingToolbarController.Item, View> entry : slots.entrySet()) {
                if (entry.getKey() != FormattingToolbarController.Item.QA_BLOCK) {
                    entry.getValue().setAlpha(othersAlpha);
                }
            }
        }

        /** Rest wide, ease in, hold zoomed — mirrored automatically by the animator's REVERSE. */
        private static float zoomFor(float t) {
            if (t <= REST_FRACTION) return 0f;
            if (t >= REST_FRACTION + RAMP_FRACTION) return 1f;
            float progress = (t - REST_FRACTION) / RAMP_FRACTION;
            return (float) ((1 - Math.cos(Math.PI * progress)) / 2);
        }
    }
}
