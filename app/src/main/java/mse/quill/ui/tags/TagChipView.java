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
import mse.quill.util.CardStyles;

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

    /**
     * Same, but only as many chips as {@code availableWidth} actually holds, with a {@code +N} chip
     * standing in for the rest — and drawn white-on-dark-text rather than in the tag's own colour.
     *
     * <p>Both are for the pinned cards. They are a fixed width, and a horizontal {@link LinearLayout}
     * hands that width out in order, so chips past the edge are allotted nothing and render as blank
     * pills — which is what a heavily tagged note used to look like. How many fit is a question about
     * the tag names, not their count: two long ones overflow where four short ones sit comfortably,
     * so the chips are measured rather than capped at a guessed number. The neutral fill is because
     * the card is itself a pastel; a tinted chip on a tinted card is colour on colour, and the tag
     * stops being legible as a separate thing.
     */
    public static void renderNeutralFitting(Context context, LinearLayout container,
                                            List<Tag> tags, int availableWidth) {
        container.removeAllViews();
        if (tags == null || tags.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);

        int spacing = CardStyles.dimen(context, R.dimen.chip_spacing);
        Chip[] chips = new Chip[tags.size()];
        // Width of the first k chips laid out in a row, so the fit below is a lookup, not a re-sum.
        int[] rowWidth = new int[tags.size() + 1];
        for (int i = 0; i < tags.size(); i++) {
            chips[i] = buildChip(context, tags.get(i), true);
            rowWidth[i + 1] = rowWidth[i] + (i == 0 ? 0 : spacing) + measureWidth(chips[i]);
        }

        int shown = tags.size();
        while (shown > 0 && rowWidth[shown] > availableWidth) shown--;
        if (shown == tags.size()) {
            for (Chip chip : chips) container.addView(chip);
            return;
        }

        // Room for the +N chip has to come out of the same width, so give back chips until both fit.
        Chip overflow = buildOverflowChip(context, tags.size() - shown);
        while (shown > 0
                && rowWidth[shown] + spacing + measureWidth(overflow) > availableWidth) {
            shown--;
            overflow = buildOverflowChip(context, tags.size() - shown);
        }
        for (int i = 0; i < shown; i++) container.addView(chips[i]);
        container.addView(overflow);
    }

    /** A chip's natural width — {@code Chip} clamps itself to {@code chip_max_width} on its own. */
    private static int measureWidth(Chip chip) {
        int unbounded = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        chip.measure(unbounded, unbounded);
        return chip.getMeasuredWidth();
    }

    /** The {@code +N} chip. Same neutral pill, no tag colour of its own — it stands for several. */
    private static Chip buildOverflowChip(Context context, int hidden) {
        Chip chip = baseChip(context);
        chip.setChipBackgroundColor(ColorStateList.valueOf(context.getColor(R.color.white)));
        chip.setText(context.getString(R.string.tag_overflow_format, hidden));
        chip.setTextColor(context.getColor(R.color.text_secondary));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(CardStyles.dimen(context, R.dimen.chip_spacing));
        chip.setLayoutParams(params);
        return chip;
    }

    /** A single tag's pill chip — exposed for the note editor's editable tag row. */
    public static Chip buildChip(Context context, Tag tag) {
        return buildChip(context, tag, false);
    }

    private static Chip buildChip(Context context, Tag tag, boolean neutral) {
        Chip chip = baseChip(context);
        chip.setChipBackgroundColor(ColorStateList.valueOf(neutral
                ? context.getColor(R.color.white)
                : ColorUtils.lighten(tag.color, CHIP_BACKGROUND_WHITE_RATIO)));
        chip.setText(tag.name);
        chip.setTextColor(neutral ? context.getColor(R.color.text_primary) : tag.color);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(CardStyles.dimen(context, R.dimen.chip_spacing));
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
        chip.setChipStartPadding(CardStyles.dimen(context, R.dimen.chip_padding_horizontal));
        chip.setChipEndPadding(CardStyles.dimen(context, R.dimen.chip_padding_horizontal));
        chip.setTextStartPadding(0f);
        chip.setTextEndPadding(0f);
        chip.setMaxWidth(CardStyles.dimen(context, R.dimen.chip_max_width));
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return chip;
    }

}
