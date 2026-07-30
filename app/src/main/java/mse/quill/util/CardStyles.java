package mse.quill.util;

import android.content.Context;

import com.google.android.material.card.MaterialCardView;

import mse.quill.R;

/**
 * The app's flat card look, shared by every list row that draws one.
 *
 * <p>Lifted out of {@code ui.home.NoteRowView} once flashcard decks needed the same treatment —
 * a deck row and a note row should be visibly the same kind of object, and that shouldn't require
 * a screen outside {@code ui.home} to reach into it.
 */
public final class CardStyles {

    private CardStyles() {}

    /**
     * The M3 theme's default {@code materialCardViewStyle} is the *outlined* one, so stroke and
     * elevation are zeroed explicitly rather than inherited — that keeps these cards looking
     * identical regardless of which default the MDC version ships. Clickable/focusable is what
     * makes MaterialCardView install its ripple foreground.
     */
    public static void applyFlatCardStyle(MaterialCardView card, int cornerRadiusRes) {
        Context context = card.getContext();
        card.setRadius(dimen(context, cornerRadiusRes));
        card.setCardElevation(0f);
        card.setStrokeWidth(0);
        card.setRippleColorResource(R.color.brand_purple_light);
        card.setClickable(true);
        card.setFocusable(true);
    }

    public static int dimen(Context context, int dimenRes) {
        return context.getResources().getDimensionPixelSize(dimenRes);
    }
}
