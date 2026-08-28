package mse.quill.ui.quiz;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

import mse.quill.R;
import mse.quill.ui.common.CardStyles;

/**
 * One answer option: a full-width tappable card.
 *
 * <p>A card rather than a radio row because an answer can be a sentence — a radio button puts a
 * 20dp target on something that spans the screen, and the row then has two places to press that
 * look different. Here the option <em>is</em> the target.
 *
 * <p>Selection is drawn as a purple outline and a tinted fill rather than a check mark: only one
 * option can be picked, so the state to communicate is "this one", not "these ones".
 */
final class QuizOptionView {

    private QuizOptionView() {}

    static final class Views {
        final MaterialCardView card;
        final TextView label;

        Views(MaterialCardView card, TextView label) {
            this.card = card;
            this.label = label;
        }
    }

    static Views build(Context context) {
        int spacingMd = CardStyles.dimen(context, R.dimen.spacing_md);
        int spacingXs = CardStyles.dimen(context, R.dimen.spacing_xs);

        MaterialCardView card = new MaterialCardView(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, spacingXs, 0, spacingXs);
        card.setLayoutParams(params);
        CardStyles.applyFlatCardStyle(card, R.dimen.quiz_option_corner_radius);
        card.setMinimumHeight(CardStyles.dimen(context, R.dimen.quiz_option_min_height));

        TextView label = new TextView(context);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.gravity = Gravity.CENTER_VERTICAL;
        label.setLayoutParams(labelParams);
        label.setPadding(spacingMd, spacingMd, spacingMd, spacingMd);
        label.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        card.addView(label);

        setSelected(card, false);
        return new Views(card, label);
    }

    /** Applies the picked/unpicked look. Stroke width is zeroed rather than made transparent so an
     *  unpicked option doesn't sit inset by a border nobody can see. */
    static void setSelected(MaterialCardView card, boolean selected) {
        Context context = card.getContext();
        card.setStrokeWidth(selected
                ? CardStyles.dimen(context, R.dimen.quiz_option_stroke_width) : 0);
        card.setStrokeColor(ContextCompat.getColor(context, R.color.brand_purple));
        card.setCardBackgroundColor(ContextCompat.getColor(context,
                selected ? R.color.brand_purple_light : R.color.surface_container));
    }
}
