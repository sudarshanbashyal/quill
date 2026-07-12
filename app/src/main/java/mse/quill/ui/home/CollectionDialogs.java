package mse.quill.ui.home;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import mse.quill.R;
import mse.quill.data.model.Collection;

/**
 * Static AlertDialog-based helpers for collection CRUD and note-move/delete actions, matching
 * the dialog idiom already used in NoteEditorFragment.showImageSourceDialog() — no dedicated
 * screen/fragment needed.
 */
public final class CollectionDialogs {

    public interface OnNameColorPicked { void onPicked(String name, int color); }
    public interface OnColorPicked { void onPicked(int color); }
    public interface OnTextPicked { void onPicked(String text); }
    public interface OnCollectionPicked { void onPicked(String collectionIdOrNull); }

    public interface ManageListener {
        void onRename();
        void onChangeColor();
        void onDelete();
    }

    private CollectionDialogs() {}

    public static void showCreateDialog(Context context, OnNameColorPicked callback) {
        int[] palette = palette(context);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, R.dimen.spacing_lg);
        container.setPadding(pad, pad, pad, pad);

        EditText nameInput = new EditText(context);
        nameInput.setHint(R.string.collection_name_hint);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        container.addView(nameInput);

        int[] selectedColor = {palette[0]};
        container.addView(buildColorPicker(context, palette, selectedColor[0], color -> selectedColor[0] = color));

        new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_new_collection_title)
                .setView(container)
                .setPositiveButton(R.string.action_create, (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (!name.isEmpty()) callback.onPicked(name, selectedColor[0]);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    public static void showRenameDialog(Context context, String currentName, OnTextPicked callback) {
        EditText nameInput = new EditText(context);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        nameInput.setText(currentName);
        nameInput.setSelection(nameInput.getText().length());
        int pad = dp(context, R.dimen.spacing_lg);
        nameInput.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_rename_collection_title)
                .setView(nameInput)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (!name.isEmpty()) callback.onPicked(name);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    public static void showColorPickerDialog(Context context, int currentColor, OnColorPicked callback) {
        int[] palette = palette(context);

        LinearLayout container = new LinearLayout(context);
        int pad = dp(context, R.dimen.spacing_lg);
        container.setPadding(pad, pad, pad, pad);

        AlertDialog[] dialogRef = new AlertDialog[1];
        container.addView(buildColorPicker(context, palette, currentColor, color -> {
            callback.onPicked(color);
            if (dialogRef[0] != null) dialogRef[0].dismiss();
        }));

        dialogRef[0] = new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_change_color_title)
                .setView(container)
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    public static void showManageDialog(Context context, Collection collection, ManageListener listener) {
        new AlertDialog.Builder(context)
                .setTitle(collection.name)
                .setItems(new String[]{
                        context.getString(R.string.action_rename),
                        context.getString(R.string.action_change_color),
                        context.getString(R.string.action_delete)
                }, (dialog, which) -> {
                    if (which == 0) listener.onRename();
                    else if (which == 1) listener.onChangeColor();
                    else listener.onDelete();
                })
                .show();
    }

    /** @param excludeCollectionId a collection id to omit from the list (e.g. the one currently open), or null. */
    public static void showAssignCollectionDialog(Context context, List<Collection> collections,
                                                   String excludeCollectionId, OnCollectionPicked callback) {
        List<Collection> options = new ArrayList<>();
        for (Collection c : collections) {
            if (!c.id.equals(excludeCollectionId)) options.add(c);
        }

        String[] items = new String[options.size() + 1];
        items[0] = context.getString(R.string.uncategorized);
        for (int i = 0; i < options.size(); i++) items[i + 1] = options.get(i).name;

        new AlertDialog.Builder(context)
                .setTitle(R.string.action_move_to_collection)
                .setItems(items, (dialog, which) ->
                        callback.onPicked(which == 0 ? null : options.get(which - 1).id))
                .show();
    }

    /** Long-press action sheet shared by the Home note list and the collection-detail note list. */
    public static void showNoteActionsDialog(Context context, List<Collection> collections,
                                              String currentCollectionId, OnCollectionPicked onMoveSelected,
                                              Runnable onDeleteConfirmed) {
        new AlertDialog.Builder(context)
                .setItems(new String[]{
                        context.getString(R.string.action_move_to_collection),
                        context.getString(R.string.action_delete_note)
                }, (dialog, which) -> {
                    if (which == 0) {
                        showAssignCollectionDialog(context, collections, currentCollectionId, onMoveSelected);
                    } else {
                        new AlertDialog.Builder(context)
                                .setTitle(R.string.delete_note_title)
                                .setMessage(R.string.delete_note_message)
                                .setPositiveButton(R.string.action_delete, (d, w) -> onDeleteConfirmed.run())
                                .setNegativeButton(R.string.action_cancel, null)
                                .show();
                    }
                })
                .show();
    }

    private static View buildColorPicker(Context context, int[] palette, int initialColor, OnColorPicked callback) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(context, R.dimen.spacing_md), 0, 0);

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

    private static int[] palette(Context context) {
        return context.getResources().getIntArray(R.array.collection_color_palette);
    }

    private static int dp(Context context, int dimenRes) {
        return context.getResources().getDimensionPixelSize(dimenRes);
    }
}
