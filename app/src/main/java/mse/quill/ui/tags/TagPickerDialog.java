package mse.quill.ui.tags;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mse.quill.R;
import mse.quill.data.TagRepository;
import mse.quill.data.model.Tag;
import mse.quill.util.TextFieldUtils;

/**
 * Dialog for attaching/removing a note's tags: a checklist of every existing tag plus an inline
 * "new tag" row (name + color swatch) that creates and immediately checks a new tag without
 * closing the dialog. Matches the MaterialAlertDialogBuilder idiom used by CollectionDialogs.
 *
 * The color swatches stay hand-drawn GradientDrawable circles on purpose: Material 3 has no
 * color-picker component, so wrapping them in a Material widget would add indirection without
 * changing how they look or behave.
 */
public final class TagPickerDialog {

    public interface OnTagsPicked { void onPicked(List<String> tagIds); }

    private interface OnColorPicked { void onPicked(int color); }

    private TagPickerDialog() {}

    public static void show(Context context, TagRepository tagRepository, List<Tag> allTags,
                             List<Tag> currentTags, OnTagsPicked callback) {
        Set<String> selectedIds = new HashSet<>();
        for (Tag tag : currentTags) selectedIds.add(tag.id);

        int pad = dp(context, R.dimen.spacing_lg);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        LinearLayout checklistContainer = new LinearLayout(context);
        checklistContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(checklistContainer);

        for (Tag tag : allTags) {
            checklistContainer.addView(buildTagRow(context, tag, selectedIds));
        }

        TextInputLayout nameField = TextFieldUtils.outlinedField(context, R.string.new_tag_hint);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = dp(context, R.dimen.spacing_md);
        nameField.setLayoutParams(nameParams);
        content.addView(nameField);

        int[] palette = context.getResources().getIntArray(R.array.swatch_color_palette);
        int[] selectedColor = {palette[0]};

        HorizontalScrollView colorScroll = new HorizontalScrollView(context);
        colorScroll.setHorizontalScrollBarEnabled(false);
        content.addView(colorScroll);
        colorScroll.addView(buildColorPicker(context, palette, selectedColor[0], color -> selectedColor[0] = color));

        MaterialButton addButton = new MaterialButton(context);
        addButton.setText(R.string.action_add_tag);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        addParams.topMargin = dp(context, R.dimen.spacing_sm);
        addButton.setLayoutParams(addParams);
        content.addView(addButton);

        addButton.setOnClickListener(v -> {
            String name = nameField.getEditText().getText().toString().trim();
            if (name.isEmpty()) return;
            tagRepository.createTag(name, selectedColor[0], newTag -> {
                selectedIds.add(newTag.id);
                checklistContainer.addView(buildTagRow(context, newTag, selectedIds));
                nameField.getEditText().setText("");
            });
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(content);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_tags_title)
                .setView(scrollView)
                .setPositiveButton(R.string.action_done, (dialog, which) ->
                        callback.onPicked(new ArrayList<>(selectedIds)))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static View buildTagRow(Context context, Tag tag, Set<String> selectedIds) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        int dotSize = dp(context, R.dimen.tag_dot_size);
        View dot = new View(context);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dotSize, dotSize);
        dotParams.setMarginEnd(dp(context, R.dimen.spacing_sm));
        dot.setLayoutParams(dotParams);
        GradientDrawable dotDrawable = new GradientDrawable();
        dotDrawable.setShape(GradientDrawable.OVAL);
        dotDrawable.setColor(tag.color);
        dot.setBackground(dotDrawable);
        row.addView(dot);

        MaterialCheckBox checkBox = new MaterialCheckBox(context);
        checkBox.setText(tag.name);
        checkBox.setChecked(selectedIds.contains(tag.id));
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) selectedIds.add(tag.id);
            else selectedIds.remove(tag.id);
        });
        row.addView(checkBox);

        return row;
    }

    private static View buildColorPicker(Context context, int[] palette, int initialColor, OnColorPicked callback) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(context, R.dimen.spacing_sm), 0, 0);

        int size = dp(context, R.dimen.swatch_size);
        int margin = dp(context, R.dimen.swatch_margin);
        View[] swatches = new View[palette.length];

        for (int i = 0; i < palette.length; i++) {
            int color = palette[i];
            View swatch = new View(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(margin);
            swatch.setLayoutParams(lp);
            swatches[i] = swatch;
            applySwatchDrawable(context, swatch, color, color == initialColor);

            swatch.setOnClickListener(v -> {
                for (int j = 0; j < palette.length; j++) {
                    applySwatchDrawable(context, swatches[j], palette[j], palette[j] == color);
                }
                callback.onPicked(color);
            });
            row.addView(swatch);
        }
        return row;
    }

    private static void applySwatchDrawable(Context context, View swatch, int color, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        if (selected) drawable.setStroke(dp(context, R.dimen.swatch_border_width), Color.BLACK);
        swatch.setBackground(drawable);
    }

    private static int dp(Context context, int dimenRes) {
        return context.getResources().getDimensionPixelSize(dimenRes);
    }
}
