package mse.quill.ui.search;

import android.content.Context;
import android.content.res.ColorStateList;
import android.widget.LinearLayout;

import com.google.android.material.chip.Chip;

import mse.quill.R;
import mse.quill.data.model.Tag;
import mse.quill.ui.tags.TagChipView;

/**
 * The chips under the search box, showing what is currently narrowing the list.
 *
 * <p>A tag filter is drawn as the tag's own chip — same colour it has on a note row — so the row
 * reads as "these tags" rather than as a separate vocabulary of filter tokens. What's added here is
 * the close icon: each chip removes its own filter, which is the fastest way out of a filter and
 * the reason the row is worth showing at all.
 */
final class FilterChips {

    private FilterChips() {}

    /** A tag's chip, in the tag's colour, with a close icon that drops it from the filter. */
    static Chip removableTag(Context context, Tag tag, Runnable onRemoved) {
        Chip chip = TagChipView.buildChip(context, tag);
        // Its own colour, dimmed to match the text — a close icon in full tag colour reads as the
        // most important thing in the chip, which it isn't.
        makeRemovable(context, chip, tag.color, onRemoved);
        return chip;
    }

    /** A plain chip for the filters that aren't tags — the pinned switch and a non-default sort. */
    static Chip removable(Context context, String label, Runnable onRemoved) {
        Chip chip = TagChipView.buildChip(context, neutralTag(context, label));
        makeRemovable(context, chip, context.getColor(R.color.text_secondary), onRemoved);
        return chip;
    }

    private static void makeRemovable(Context context, Chip chip, int iconColor, Runnable onRemoved) {
        chip.setCloseIconVisible(true);
        chip.setCloseIconResource(R.drawable.ic_clear);
        chip.setCloseIconTint(ColorStateList.valueOf(iconColor));
        chip.setCloseIconSize(context.getResources()
                .getDimensionPixelSize(R.dimen.filter_chip_close_size));
        // The whole chip removes it, not only the little icon — at this size the icon alone is a
        // smaller target than a fingertip.
        chip.setOnClickListener(v -> onRemoved.run());
        chip.setOnCloseIconClickListener(v -> onRemoved.run());

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(context.getResources().getDimensionPixelSize(R.dimen.chip_spacing));
        chip.setLayoutParams(params);
    }

    /** A stand-in tag so non-tag filters get the identical pill without a second builder. */
    private static Tag neutralTag(Context context, String label) {
        Tag tag = new Tag();
        tag.name = label;
        tag.color = context.getColor(R.color.text_secondary);
        return tag;
    }
}
