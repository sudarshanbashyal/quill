package mse.quill.ui.tags;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import mse.quill.R;
import mse.quill.data.model.Tag;
import mse.quill.util.ColorUtils;

/** Renders a note's tags as small pastel pill chips — reused on note rows, pinned cards, and the editor. */
public final class TagChipView {

    private static final float CHIP_BACKGROUND_WHITE_RATIO = 0.82f;

    private TagChipView() {}

    /** Rebuilds {@code container} with one pill chip per tag, hiding it entirely when there are none. */
    public static void render(Context context, LinearLayout container, List<Tag> tags) {
        container.removeAllViews();
        if (tags == null || tags.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        for (Tag tag : tags) container.addView(buildChip(context, tag));
    }

    /** A single tag's pill chip — exposed for the note editor's editable tag row. */
    public static TextView buildChip(Context context, Tag tag) {
        int paddingH = dimen(context, R.dimen.chip_padding_horizontal);
        int paddingV = dimen(context, R.dimen.chip_padding_vertical);
        int cornerRadius = dimen(context, R.dimen.chip_corner_radius);
        int marginEnd = dimen(context, R.dimen.chip_spacing);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(cornerRadius);
        background.setColor(ColorUtils.lighten(tag.color, CHIP_BACKGROUND_WHITE_RATIO));

        TextView chip = new TextView(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(marginEnd);
        chip.setLayoutParams(params);
        chip.setPadding(paddingH, paddingV, paddingH, paddingV);
        chip.setBackground(background);
        chip.setMaxWidth(dimen(context, R.dimen.chip_max_width));
        chip.setMaxLines(1);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setText(tag.name);
        chip.setTextColor(tag.color);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return chip;
    }

    /** The "+ Add tag" affordance chip shown in the note editor's editable tag row. */
    public static TextView buildAddChip(Context context) {
        int paddingH = dimen(context, R.dimen.chip_padding_horizontal);
        int paddingV = dimen(context, R.dimen.chip_padding_vertical);
        int cornerRadius = dimen(context, R.dimen.chip_corner_radius);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(cornerRadius);
        background.setColor(context.getColor(R.color.divider));

        TextView chip = new TextView(context);
        chip.setPadding(paddingH, paddingV, paddingH, paddingV);
        chip.setBackground(background);
        chip.setText(R.string.action_add_tag);
        chip.setTextColor(context.getColor(R.color.text_secondary));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return chip;
    }

    private static int dimen(Context context, int dimenRes) {
        return context.getResources().getDimensionPixelSize(dimenRes);
    }
}
