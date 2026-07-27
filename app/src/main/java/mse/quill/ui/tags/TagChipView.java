package mse.quill.ui.tags;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

import com.google.android.material.chip.Chip;
import com.google.android.material.shape.RelativeCornerSize;

import java.util.List;

import mse.quill.R;
import mse.quill.data.model.Tag;
import mse.quill.util.ColorUtils;

/**
 * Renders a note's tags as small pastel pill chips — reused on note rows, pinned cards, and the
 * editor. POC for migrating hand-rolled pill views onto Material 3's {@link Chip} instead of a
 * manually drawn {@code GradientDrawable}.
 */
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
    public static Chip buildChip(Context context, Tag tag) {
        Chip chip = baseChip(context);
        chip.setChipBackgroundColor(
                ColorStateList.valueOf(ColorUtils.lighten(tag.color, CHIP_BACKGROUND_WHITE_RATIO)));
        chip.setText(tag.name);
        chip.setTextColor(tag.color);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dimen(context, R.dimen.chip_spacing));
        chip.setLayoutParams(params);
        return chip;
    }

    /** The "+ Add tag" affordance chip shown in the note editor's editable tag row. */
    public static Chip buildAddChip(Context context) {
        Chip chip = baseChip(context);
        chip.setChipBackgroundColor(ColorStateList.valueOf(context.getColor(R.color.divider)));
        chip.setText(R.string.action_add_tag);
        chip.setTextColor(context.getColor(R.color.text_secondary));
        return chip;
    }

    /** Shared pill styling: no icon/checkmark, tight touch target, same corner/padding as before. */
    private static Chip baseChip(Context context) {
        Chip chip = new Chip(context);
        chip.setCheckable(false);
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipIconVisible(false);
        chip.setCheckedIconVisible(false);
        chip.setCloseIconVisible(false);
        chip.setChipStrokeWidth(0f);
        // Pill shape via a relative (half-height) corner size rather than the deprecated
        // setChipCornerRadius + a 999dp sentinel — this stays a pill at any chip height.
        chip.setShapeAppearanceModel(chip.getShapeAppearanceModel().toBuilder()
                .setAllCornerSizes(new RelativeCornerSize(0.5f))
                .build());
        chip.setChipMinHeightResource(R.dimen.chip_min_height);
        chip.setChipStartPadding(dimen(context, R.dimen.chip_padding_horizontal));
        chip.setChipEndPadding(dimen(context, R.dimen.chip_padding_horizontal));
        chip.setTextStartPadding(0f);
        chip.setTextEndPadding(0f);
        chip.setMaxWidth(dimen(context, R.dimen.chip_max_width));
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return chip;
    }

    private static int dimen(Context context, int dimenRes) {
        return context.getResources().getDimensionPixelSize(dimenRes);
    }
}
