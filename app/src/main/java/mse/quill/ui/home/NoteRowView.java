package mse.quill.ui.home;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import mse.quill.R;
import mse.quill.util.CardStyles;

/**
 * Builds a note row entirely in code rather than via an XML layout + LayoutInflater.
 *
 * On this SDK, the very first XML-attribute-derived LayoutParams resolved within a given
 * LayoutInflater.inflate() call throws "You must supply a layout_width attribute" — reproducible
 * with a bare single-TextView layout, independent of RecyclerView, GridLayoutManager, or item
 * content (see HomeAdapter's history/comments). Building views with `new View(context)` +
 * explicit LayoutParams objects (as CollectionDialogs's color swatch picker and
 * FormattingToolbarController already do successfully elsewhere in this codebase) sidesteps that
 * codepath entirely. Material 3 widgets are constructed the same way — programmatically — so the
 * migration off hand-drawn GradientDrawables didn't have to reintroduce the inflater.
 *
 * The root is a filled {@link MaterialCardView}: the MSE Figma file draws note rows as flat grey
 * rounded rows, so elevation stays at 0 and the tonal fill does the work. Ripple comes from the
 * card itself once it's clickable, replacing the old ?attr/selectableItemBackground.
 */
final class NoteRowView {

    private NoteRowView() {}

    static final class Views {
        final View root;
        final TextView titleView;
        final TextView timestampView;
        final LinearLayout tagsContainer;

        Views(View root, TextView titleView, TextView timestampView, LinearLayout tagsContainer) {
            this.root = root;
            this.titleView = titleView;
            this.timestampView = timestampView;
            this.tagsContainer = tagsContainer;
        }
    }

    static Views build(Context context) {
        int spacingMd = dimen(context, R.dimen.spacing_md);
        int spacingSm = dimen(context, R.dimen.spacing_sm);
        int gutter = dimen(context, R.dimen.list_item_gutter);
        int marginVertical = dimen(context, R.dimen.note_row_margin_vertical);

        MaterialCardView card = new MaterialCardView(context);
        RecyclerView.LayoutParams cardParams = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(gutter, marginVertical, gutter, marginVertical);
        card.setLayoutParams(cardParams);
        applyFlatCardStyle(card, R.dimen.note_row_corner_radius);
        card.setCardBackgroundColor(context.getColor(R.color.surface_container));

        LinearLayout outer = new LinearLayout(context);
        outer.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(spacingMd, spacingMd, spacingMd, spacingMd);
        card.addView(outer);

        LinearLayout row = new LinearLayout(context);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        outer.addView(row);

        TextView title = new TextView(context);
        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMarginEnd(spacingSm);
        title.setLayoutParams(titleParams);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(title);

        TextView timestamp = new TextView(context);
        timestamp.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        timestamp.setAlpha(0.6f);
        timestamp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(timestamp);

        LinearLayout tagsContainer = new LinearLayout(context);
        LinearLayout.LayoutParams tagsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagsParams.topMargin = spacingSm;
        tagsContainer.setLayoutParams(tagsParams);
        tagsContainer.setOrientation(LinearLayout.HORIZONTAL);
        tagsContainer.setVisibility(View.GONE);
        outer.addView(tagsContainer);

        return new Views(card, title, timestamp, tagsContainer);
    }

    /** Lives in {@link CardStyles} now that flashcard decks draw the same card; these two stay as
     *  delegates so this package's call sites read the way they always have. */
    static void applyFlatCardStyle(MaterialCardView card, int cornerRadiusRes) {
        CardStyles.applyFlatCardStyle(card, cornerRadiusRes);
    }

    static int dimen(Context context, int dimenRes) {
        return CardStyles.dimen(context, dimenRes);
    }
}
