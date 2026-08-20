package mse.quill.ui.search;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;

import java.util.List;

import mse.quill.R;
import mse.quill.data.model.Tag;
import mse.quill.util.ColorUtils;
import mse.quill.util.MaxHeightScrollView;

/**
 * Sorting and tag filtering, behind the search bar's filter button.
 *
 * <p>Edits a copy and only commits on Apply, so backing out of the sheet leaves the list exactly as
 * it was — the alternative, filtering live behind the dialog, means the result is hidden by the
 * dialog covering it.
 */
public final class SearchFilterDialog {

    /** Matches TagChipView's tint, so a tag looks the same here as it does on a note. */
    private static final float CHIP_FILL_WHITE_RATIO = 0.82f;

    public interface Listener {
        void onFilterApplied();
    }

    private SearchFilterDialog() {}

    public static void show(Context context, NoteFilter filter, List<Tag> allTags,
                            Listener listener) {
        int pad = dimen(context, R.dimen.spacing_lg);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, dimen(context, R.dimen.spacing_md), pad, 0);

        content.addView(sectionLabel(context, R.string.filter_sort_by, false));
        RadioGroup sortGroup = buildSortGroup(context, filter);
        content.addView(sortGroup);

        ChipGroup tagGroup = null;
        if (!allTags.isEmpty()) {
            content.addView(sectionLabel(context, R.string.filter_by_tag, true));
            tagGroup = buildTagGroup(context, filter, allTags);
            content.addView(tagGroup);
        }

        // Scrolls once there are enough tags to outgrow the screen, and only then — see
        // MaxHeightScrollView for why a plain fixed height is wrong here.
        MaxHeightScrollView scroll = new MaxHeightScrollView(context);
        scroll.setMaxHeight(dimen(context, R.dimen.filter_sheet_max_height));
        scroll.addView(content);

        final ChipGroup tags = tagGroup;
        new MaterialAlertDialogBuilder(context)
                .setView(scroll)
                .setPositiveButton(R.string.action_apply, (dialog, which) -> {
                    commit(filter, sortGroup, tags);
                    listener.onFilterApplied();
                })
                .setNeutralButton(R.string.action_clear_filters, (dialog, which) -> {
                    filter.clear();
                    listener.onFilterApplied();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static void commit(NoteFilter filter, RadioGroup sortGroup, ChipGroup tagGroup) {
        NoteFilter.Sort[] sorts = NoteFilter.Sort.values();
        int checked = sortGroup.getCheckedRadioButtonId();
        if (checked >= 0 && checked < sorts.length) filter.setSort(sorts[checked]);

        if (tagGroup != null) {
            for (int i = 0; i < tagGroup.getChildCount(); i++) {
                Chip chip = (Chip) tagGroup.getChildAt(i);
                String tagId = (String) chip.getTag();
                boolean wanted = chip.isChecked();
                if (wanted != filter.hasTag(tagId)) filter.toggleTag(tagId);
            }
        }
    }

    private static RadioGroup buildSortGroup(Context context, NoteFilter filter) {
        RadioGroup group = new RadioGroup(context);
        group.setOrientation(RadioGroup.VERTICAL);
        NoteFilter.Sort[] sorts = NoteFilter.Sort.values();
        for (int i = 0; i < sorts.length; i++) {
            RadioButton button = new RadioButton(context);
            // The id is the ordinal, which is what commit() reads back — no parallel lookup table.
            button.setId(i);
            button.setText(sorts[i].labelRes);
            button.setTextColor(context.getColor(R.color.text_primary));
            group.addView(button);
        }
        group.check(filter.sort().ordinal());
        return group;
    }

    /**
     * Tags as checkable chips in their own colours, so picking one looks like the thing it filters.
     *
     * <p>Selection is <em>only</em> the border colour, and every chip carries that border at all
     * times — unselected it is painted the same colour as the fill, so it is there but invisible.
     * That is what keeps the width fixed: a stroke that appears on selection would re-measure the
     * chip, and a checked icon is worse, adding a glyph and its padding so the row visibly
     * reflows on every tap. Nothing about the box changes on selection except one colour.
     *
     * <p>Driven by a {@link ColorStateList} rather than set on toggle, so the chip restyles itself
     * without a listener.
     */
    private static ChipGroup buildTagGroup(Context context, NoteFilter filter, List<Tag> allTags) {
        ChipGroup group = new ChipGroup(context);
        group.setSingleSelection(false);

        for (Tag tag : allTags) {
            int fill = ColorUtils.lighten(tag.color, CHIP_FILL_WHITE_RATIO);

            Chip chip = new Chip(context);
            chip.setText(tag.name);
            chip.setTag(tag.id);
            chip.setCheckable(true);
            chip.setTextColor(tag.color);
            chip.setChipBackgroundColor(ColorStateList.valueOf(fill));

            chip.setChipStrokeColor(new ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{tag.color, fill}));
            chip.setChipStrokeWidth(context.getResources()
                    .getDimensionPixelSize(R.dimen.filter_chip_selected_stroke));

            // The tick would widen the chip; the border is carrying the state on its own.
            chip.setCheckedIconVisible(false);

            chip.setChecked(filter.hasTag(tag.id));
            group.addView(chip);
        }
        return group;
    }

    private static View sectionLabel(Context context, int textRes, boolean spaceAbove) {
        MaterialTextView label = new MaterialTextView(context);
        label.setText(textRes);
        label.setTextColor(context.getColor(R.color.text_secondary));
        label.setAllCaps(true);
        label.setTextSize(12f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (spaceAbove) params.topMargin = dimen(context, R.dimen.spacing_md);
        params.bottomMargin = dimen(context, R.dimen.spacing_xs);
        label.setLayoutParams(params);
        return label;
    }

    private static int dimen(Context context, int dimenRes) {
        return context.getResources().getDimensionPixelSize(dimenRes);
    }
}
