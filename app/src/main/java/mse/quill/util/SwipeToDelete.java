package mse.quill.util;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import mse.quill.R;

/**
 * Swipe a full-width row either way to delete it.
 *
 * <p>Both directions, rather than the one-direction-per-action split some apps use: there is only
 * one thing a row here can do, so making the user remember which way it lives on would be a rule
 * with nothing behind it. Only rows the {@link Target} claims are swipeable — a grid of collection
 * or whiteboard cards sits in the same list on Home, and a card that slid away under a full-width
 * red panel would be describing a gesture the layout doesn't have.
 *
 * <p>The panel underneath is drawn rather than laid out. It is a transient decoration of the
 * <em>gap</em> the row leaves behind, not a view anything can interact with, so there is no widget
 * for it to be: {@code onChildDraw} is where ItemTouchHelper expects it, and giving every row a
 * permanently-hidden background view instead would double the item count for something visible for
 * a third of a second.
 */
public final class SwipeToDelete {

    /** What a given row is, and what to do when it goes. */
    public interface Target {
        /** Whether this row can be swiped away at all. */
        boolean isSwipeable(RecyclerView.ViewHolder holder);
        /** The row has been swiped clear; hide it and offer the undo. */
        void onSwiped(RecyclerView.ViewHolder holder);
    }

    /**
     * How far across its own width a row has to be dragged before letting go deletes it.
     *
     * <p>Well past the default half. Deleting is not undoable-by-shrug — the undo bar is a few
     * seconds, and after that a deck's review history or a quiz's scores are gone — so the gesture
     * should take a deliberate pull rather than the flick that scrolling a list sometimes produces
     * sideways by accident.
     */
    private static final float SWIPE_THRESHOLD = 0.7f;

    /**
     * How much faster than usual a fling has to be to delete on velocity alone.
     *
     * <p>Raising the distance threshold on its own doesn't do it: ItemTouchHelper also dismisses
     * anything thrown hard enough, whatever distance it covered, so a short sharp flick would still
     * take the row. Effectively out of reach, which leaves distance as the only way through and
     * makes the gesture mean one thing.
     */
    private static final float ESCAPE_VELOCITY_MULTIPLIER = 8f;

    /**
     * Whether ItemTouchHelper is currently dragging a row sideways.
     *
     * <p>Read by {@code MainActivity}'s swipe-between-tabs gesture, which shares the horizontal
     * axis with this one. The row wins wherever there is a row: the tab gesture asks this before
     * acting, so a note being dragged towards deletion can't also carry the screen to Flashcards.
     */
    private static int activeSwipes = 0;

    public static boolean isSwipeInProgress() {
        return activeSwipes > 0;
    }

    private SwipeToDelete() {}

    public static ItemTouchHelper attach(RecyclerView recyclerView, Target target) {
        Drawable icon = ContextCompat.getDrawable(recyclerView.getContext(), R.drawable.ic_delete);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(ContextCompat.getColor(recyclerView.getContext(), R.color.text_on_brand));
        }
        Drawable deleteIcon = icon;
        int panelColour = ContextCompat.getColor(recyclerView.getContext(), R.color.danger);
        int iconSize = recyclerView.getResources().getDimensionPixelSize(R.dimen.swipe_delete_icon);
        int inset = recyclerView.getResources().getDimensionPixelSize(R.dimen.swipe_delete_inset);
        float corner = recyclerView.getResources().getDimension(R.dimen.swipe_delete_corner);

        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {

            private final android.graphics.Paint panel = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override
            public int getSwipeDirs(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder h) {
                return target.isSwipeable(h) ? super.getSwipeDirs(rv, h) : 0;
            }

            @Override
            public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder holder) {
                return SWIPE_THRESHOLD;
            }

            @Override
            public float getSwipeEscapeVelocity(float defaultValue) {
                return defaultValue * ESCAPE_VELOCITY_MULTIPLIER;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder h,
                                  @NonNull RecyclerView.ViewHolder t) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                target.onSwiped(holder);
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder holder, int actionState) {
                super.onSelectedChanged(holder, actionState);
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) activeSwipes++;
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder h) {
                super.clearView(rv, h);
                if (activeSwipes > 0) activeSwipes--;
            }

            @Override
            public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView rv,
                                    @NonNull RecyclerView.ViewHolder holder, float dX, float dY,
                                    int actionState, boolean isCurrentlyActive) {
                View row = holder.itemView;
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0) {
                    // Inset to the row's own margins so the panel is the shape of the thing being
                    // removed rather than a bar across the whole screen.
                    float top = row.getTop() + inset;
                    float bottom = row.getBottom() - inset;
                    panel.setColor(panelColour);
                    float left = dX > 0 ? row.getLeft() : row.getRight() + dX;
                    float right = dX > 0 ? row.getLeft() + dX : row.getRight();
                    canvas.drawRoundRect(left, top, right, bottom, corner, corner, panel);

                    if (deleteIcon != null) {
                        // Pinned to the edge the row is uncovering, so the icon appears from under
                        // the row rather than travelling with it.
                        int centreY = (int) ((top + bottom) / 2);
                        int iconTop = centreY - iconSize / 2;
                        int iconLeft = dX > 0
                                ? row.getLeft() + inset * 2
                                : row.getRight() - inset * 2 - iconSize;
                        // Only once the panel is wide enough to hold it, so the glyph doesn't spill
                        // out over the row at the start of the gesture — which, with the long pull
                        // this now takes, is also roughly when the drag stops looking accidental.
                        if (Math.abs(dX) > iconSize + inset * 4) {
                            deleteIcon.setBounds(new Rect(
                                    iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize));
                            deleteIcon.draw(canvas);
                        }
                    }
                }
                super.onChildDraw(canvas, rv, holder, dX, dY, actionState, isCurrentlyActive);
            }
        });
        helper.attachToRecyclerView(recyclerView);
        return helper;
    }
}
