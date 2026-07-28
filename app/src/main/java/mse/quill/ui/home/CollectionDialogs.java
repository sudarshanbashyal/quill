package mse.quill.ui.home;

import android.content.Context;
import android.widget.LinearLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

import mse.quill.R;
import mse.quill.data.model.Collection;
import mse.quill.util.TextFieldUtils;

/**
 * Static MaterialAlertDialogBuilder-based helpers for collection CRUD and note-move/delete
 * actions, matching the dialog idiom already used in NoteEditorFragment.showImageSourceDialog()
 * — no dedicated screen/fragment needed.
 */
public final class CollectionDialogs {

    public interface OnTextPicked { void onPicked(String text); }
    public interface OnCollectionPicked { void onPicked(String collectionIdOrNull); }

    public interface ManageListener {
        void onRename();
        void onDelete();
    }

    private CollectionDialogs() {}

    public static void showCreateDialog(Context context, OnTextPicked callback) {
        TextInputLayout nameField = TextFieldUtils.outlinedField(context, R.string.collection_name_hint);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_new_collection_title)
                .setView(inset(context, nameField))
                .setPositiveButton(R.string.action_create, (dialog, which) -> {
                    String name = nameField.getEditText().getText().toString().trim();
                    if (!name.isEmpty()) callback.onPicked(name);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    public static void showRenameDialog(Context context, String currentName, OnTextPicked callback) {
        TextInputLayout nameField = TextFieldUtils.outlinedField(context, R.string.collection_name_hint);
        nameField.getEditText().setText(currentName);
        nameField.getEditText().setSelection(currentName.length());

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_rename_collection_title)
                .setView(inset(context, nameField))
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String name = nameField.getEditText().getText().toString().trim();
                    if (!name.isEmpty()) callback.onPicked(name);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Dialog setView() places content flush against the dialog edges; the field needs breathing room. */
    private static LinearLayout inset(Context context, TextInputLayout field) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, R.dimen.spacing_lg);
        container.setPadding(pad, dp(context, R.dimen.spacing_sm), pad, 0);
        container.addView(field);
        return container;
    }

    public static void showManageDialog(Context context, Collection collection, ManageListener listener) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(collection.name)
                .setItems(new String[]{
                        context.getString(R.string.action_rename),
                        context.getString(R.string.action_delete)
                }, (dialog, which) -> {
                    if (which == 0) listener.onRename();
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

        String[] items = new String[options.size()];
        for (int i = 0; i < options.size(); i++) items[i] = options.get(i).name;

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.action_move_to_collection)
                .setItems(items, (dialog, which) -> callback.onPicked(options.get(which).id))
                .show();
    }

    /** Long-press action sheet shared by the Home note list, the pinned-notes row, and the
     *  collection-detail note list. */
    public static void showNoteActionsDialog(Context context, List<Collection> collections,
                                              String currentCollectionId, boolean isPinned,
                                              OnCollectionPicked onMoveSelected, Runnable onPinToggle,
                                              Runnable onDeleteConfirmed) {
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        items.add(context.getString(isPinned ? R.string.action_unpin : R.string.action_pin));
        actions.add(onPinToggle);

        items.add(context.getString(R.string.action_move_to_collection));
        actions.add(() -> showAssignCollectionDialog(context, collections, currentCollectionId, onMoveSelected));

        if (currentCollectionId != null) {
            items.add(context.getString(R.string.action_remove_from_collection));
            actions.add(() -> onMoveSelected.onPicked(null));
        }

        items.add(context.getString(R.string.action_delete_note));
        actions.add(() -> new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.delete_note_title)
                .setMessage(R.string.delete_note_message)
                .setPositiveButton(R.string.action_delete, (d, w) -> onDeleteConfirmed.run())
                .setNegativeButton(R.string.action_cancel, null)
                .show());

        new MaterialAlertDialogBuilder(context)
                .setItems(items.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    private static int dp(Context context, int dimenRes) {
        return context.getResources().getDimensionPixelSize(dimenRes);
    }
}
