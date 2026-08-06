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

/**
 * Builds a whiteboard-grid card in code — same reasons and same flat-card styling as
 * {@link CollectionCardView}; see {@link NoteRowView} for the inflater history behind it.
 *
 * A glyph badge stands in for a thumbnail: rendering a real preview would mean loading every
 * board's strokes just to draw Home, and the boards have no cover image to cache instead.
 */
final class WhiteboardCardView {

    private WhiteboardCardView() {}

    static final class Views {
        final MaterialCardView root;
        final TextView titleView;
        final TextView countView;
        final TextView updatedView;

        Views(MaterialCardView root, TextView titleView, TextView countView, TextView updatedView) {
            this.root = root;
            this.titleView = titleView;
            this.countView = countView;
            this.updatedView = updatedView;
        }
    }

    static Views build(Context context) {
        int spacingSm = NoteRowView.dimen(context, R.dimen.spacing_sm);
        int spacingXs = NoteRowView.dimen(context, R.dimen.spacing_xs);
        int spacingMd = NoteRowView.dimen(context, R.dimen.spacing_md);
        int minHeight = (int) (110 * context.getResources().getDisplayMetrics().density);

        MaterialCardView root = new MaterialCardView(context);
        RecyclerView.LayoutParams rootParams = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
        rootParams.setMargins(spacingSm, spacingSm, spacingSm, spacingSm);
        root.setLayoutParams(rootParams);
        root.setMinimumHeight(minHeight);
        NoteRowView.applyFlatCardStyle(root, R.dimen.card_corner_radius);
        root.setCardBackgroundColor(context.getColor(R.color.surface_container));

        LinearLayout detailGroup = new LinearLayout(context);
        detailGroup.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        detailGroup.setOrientation(LinearLayout.VERTICAL);
        detailGroup.setPadding(spacingMd, spacingMd, spacingMd, spacingMd);
        root.addView(detailGroup);

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.TOP);
        detailGroup.addView(titleRow);

        TextView icon = new TextView(context);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iconParams.setMarginEnd(spacingSm);
        icon.setLayoutParams(iconParams);
        icon.setText(R.string.whiteboard_icon_glyph);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleRow.addView(icon);

        TextView title = new TextView(context);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        titleRow.addView(title);

        TextView count = new TextView(context);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countParams.topMargin = spacingXs;
        count.setLayoutParams(countParams);
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        detailGroup.addView(count);

        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        detailGroup.addView(spacer);

        TextView updated = new TextView(context);
        updated.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        updated.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        updated.setAlpha(0.7f);
        detailGroup.addView(updated);

        return new Views(root, title, count, updated);
    }
}
