package mse.quill.ui.home;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;

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

    public interface OnTextPicked { void onPicked(String text); }
    public interface OnCollectionPicked { void onPicked(String collectionIdOrNull); }

    public interface ManageListener {
        void onRename();
        void onDelete();
    }

    private CollectionDialogs() {}

    public static void showCreateDialog(Context context, OnTextPicked callback) {
        EditText nameInput = new EditText(context);
        nameInput.setHint(R.string.collection_name_hint);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        int pad = dp(context, R.dimen.spacing_lg);
        nameInput.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_new_collection_title)
                .setView(nameInput)
                .setPositiveButton(R.string.action_create, (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (!name.isEmpty()) callback.onPicked(name);
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

    public static void showManageDialog(Context context, Collection collection, ManageListener listener) {
        new AlertDialog.Builder(context)
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

        new AlertDialog.Builder(context)
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
        actions.add(() -> new AlertDialog.Builder(context)
                .setTitle(R.string.delete_note_title)
                .setMessage(R.string.delete_note_message)
                .setPositiveButton(R.string.action_delete, (d, w) -> onDeleteConfirmed.run())
                .setNegativeButton(R.string.action_cancel, null)
                .show());

        new AlertDialog.Builder(context)
                .setItems(items.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    private static int dp(Context context, int dimenRes) {
        return context.getResources().getDimensionPixelSize(dimenRes);
    }
}
