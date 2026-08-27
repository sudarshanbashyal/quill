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
import mse.quill.util.CardStyles;

/**
 * Builds a collection-grid card entirely in code — see NoteRowView for why, and for the shared
 * flat-card styling these cards get instead of a hand-drawn GradientDrawable.
 */
final class CollectionCardView {

    private CollectionCardView() {}

    static final class Views {
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
        int gutter = CardStyles.dimen(context, R.dimen.list_item_gutter);
        int spacingXs = CardStyles.dimen(context, R.dimen.spacing_xs);
        int spacingMd = CardStyles.dimen(context, R.dimen.spacing_md);
        int minHeight = (int) (124 * context.getResources().getDisplayMetrics().density);

        MaterialCardView root = new MaterialCardView(context);
        RecyclerView.LayoutParams rootParams = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
        rootParams.setMargins(gutter, gutter, gutter, gutter);
        root.setLayoutParams(rootParams);
        root.setMinimumHeight(minHeight);
        NoteRowView.applyFlatCardStyle(root, R.dimen.card_corner_radius);
        // The same tonal grey as note rows. The collection's own colour used to fill the card, but
        // a grid of pastel tiles next to grey note rows read as two different kinds of thing.
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

        // Holds the dot-separated "N notes · N flashcards · N quizzes" summary, which runs to a
        // second line on a half-width card once a collection has all three.
        TextView count = new TextView(context);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countParams.topMargin = spacingXs;
        count.setLayoutParams(countParams);
        count.setMaxLines(2);
        count.setEllipsize(android.text.TextUtils.TruncateAt.END);
        count.setTextColor(context.getColor(R.color.text_secondary));
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
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
