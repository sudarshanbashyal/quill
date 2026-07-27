package mse.quill.ui.home;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import mse.quill.R;

/**
 * Builds a collection-grid card entirely in code — see NoteRowView for why, and for the shared
 * flat-card styling these cards get instead of a hand-drawn GradientDrawable.
 */
final class CollectionCardView {

    private CollectionCardView() {}

    static final class Views {
        /** Exposed so the adapter can retint the fill per collection via setCardBackgroundColor. */
        final MaterialCardView root;
        final TextView nameView;
        final TextView countView;
        final TextView updatedView;

        Views(MaterialCardView root, TextView nameView, TextView countView, TextView updatedView) {
            this.root = root;
            this.nameView = nameView;
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

        TextView name = new TextView(context);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        detailGroup.addView(name);

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

        return new Views(root, name, count, updated);
    }
}
