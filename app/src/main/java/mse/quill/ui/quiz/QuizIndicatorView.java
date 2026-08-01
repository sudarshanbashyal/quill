package mse.quill.ui.quiz;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import mse.quill.R;
import mse.quill.util.CardStyles;

/**
 * One pip in the row of question indicators.
 *
 * <p>The row exists because the answer sheet can be filled in any order: without it, "have I left
 * anything blank?" could only be answered by paging through the whole quiz, which costs time the
 * clock is charging for. Each pip carries its question's number and is tappable, so the row is
 * navigation as well as status.
 *
 * <p>Three states, deliberately distinguishable by fill and not only by colour: answered is solid,
 * blank is flat grey, and whichever question is on screen carries a ring — so the current
 * <em>blank</em> question and the current <em>answered</em> one are both recognisable.
 */
final class QuizIndicatorView {

    private QuizIndicatorView() {}

    static TextView build(Context context) {
        int size = CardStyles.dimen(context, R.dimen.quiz_indicator_size);

        TextView pip = new TextView(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMarginEnd(CardStyles.dimen(context, R.dimen.quiz_indicator_spacing));
        pip.setLayoutParams(params);
        pip.setGravity(Gravity.CENTER);
        pip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pip.setTypeface(pip.getTypeface(), Typeface.BOLD);
        pip.setClickable(true);
        pip.setFocusable(true);
        return pip;
    }

    static void setState(TextView pip, boolean answered, boolean current) {
        Context context = pip.getContext();

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(ContextCompat.getColor(context,
                answered ? R.color.brand_purple : R.color.surface_container));
        if (current) {
            background.setStroke(CardStyles.dimen(context, R.dimen.quiz_indicator_stroke),
                    ContextCompat.getColor(context, R.color.brand_purple_dark));
        }
        pip.setBackground(background);
        pip.setTextColor(ContextCompat.getColor(context,
                answered ? R.color.text_on_brand
                         : (current ? R.color.brand_purple_dark : R.color.text_secondary)));
    }
}
