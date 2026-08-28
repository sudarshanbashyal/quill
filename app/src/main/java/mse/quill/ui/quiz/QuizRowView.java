package mse.quill.ui.quiz;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import mse.quill.R;
import mse.quill.ui.common.CardStyles;

/**
 * One quiz row: a best-score badge, the note's title with two lines of detail, and a delete button.
 *
 * <p>Built in code rather than inflated, for the reason documented on {@code ui.home.NoteRowView}.
 * Structurally a copy of {@code FlashcardDeckRowView} because the two lists sit behind adjacent
 * tabs and should read as the same kind of object — only the number in the badge differs: a deck
 * leads with work outstanding, a quiz with how well it has gone.
 */
final class QuizRowView {

    private QuizRowView() {}

    static final class Views {
        final View root;
        final TextView badge;
        final TextView title;
        final TextView detail;
        final TextView meta;
        final View deleteButton;

        Views(View root, TextView badge, TextView title, TextView detail, TextView meta,
              View deleteButton) {
            this.root = root;
            this.badge = badge;
            this.title = title;
            this.detail = detail;
            this.meta = meta;
            this.deleteButton = deleteButton;
        }
    }

    static Views build(Context context) {
        int spacingMd = CardStyles.dimen(context, R.dimen.spacing_md);
        int spacingSm = CardStyles.dimen(context, R.dimen.spacing_sm);
        int spacingXs = CardStyles.dimen(context, R.dimen.spacing_xs);

        MaterialCardView card = new MaterialCardView(context);
        RecyclerView.LayoutParams cardParams = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(spacingMd, spacingXs, spacingMd, spacingXs);
        card.setLayoutParams(cardParams);
        CardStyles.applyFlatCardStyle(card, R.dimen.note_row_corner_radius);
        card.setCardBackgroundColor(context.getColor(R.color.surface_container));

        LinearLayout row = new LinearLayout(context);
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(spacingSm, spacingSm, spacingSm, spacingSm);
        card.addView(row);

        TextView badge = new TextView(context);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeParams.setMarginEnd(spacingSm);
        badge.setLayoutParams(badgeParams);
        badge.setMinWidth(CardStyles.dimen(context, R.dimen.quiz_badge_min_width));
        badge.setBackgroundResource(R.drawable.bg_due_badge);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(spacingSm, spacingXs, spacingSm, spacingXs);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        badge.setTypeface(badge.getTypeface(), Typeface.BOLD);
        row.addView(badge);

        LinearLayout column = new LinearLayout(context);
        column.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        column.setOrientation(LinearLayout.VERTICAL);
        row.addView(column);

        TextView title = new TextView(context);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        column.addView(title);

        TextView detail = new TextView(context);
        detail.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        detail.setMaxLines(1);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        detail.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        column.addView(detail);

        TextView meta = new TextView(context);
        meta.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        meta.setMaxLines(1);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        meta.setAlpha(0.8f);
        column.addView(meta);

        ImageView delete = new ImageView(context);
        int deleteSize = CardStyles.dimen(context, R.dimen.deck_delete_size);
        delete.setLayoutParams(new LinearLayout.LayoutParams(deleteSize, deleteSize));
        int iconInset = (deleteSize - CardStyles.dimen(context, R.dimen.deck_delete_icon_size)) / 2;
        delete.setPadding(iconInset, iconInset, iconInset, iconInset);
        delete.setImageResource(R.drawable.ic_clear);
        ImageViewCompat.setImageTintList(delete, ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.text_secondary)));
        delete.setContentDescription(context.getString(R.string.action_delete_quiz));
        delete.setClickable(true);
        delete.setFocusable(true);
        delete.setBackground(borderlessRipple(context));
        row.addView(delete);

        return new Views(card, badge, title, detail, meta, delete);
    }

    private static Drawable borderlessRipple(Context context) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, value, true);
        return ContextCompat.getDrawable(context, value.resourceId);
    }
}
