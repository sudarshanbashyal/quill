package mse.quill.ui.home;

import android.content.Context;
import android.widget.LinearLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

import mse.quill.R;
import mse.quill.data.model.Collection;
import mse.quill.ui.collections.CollectionLockFlow;
import mse.quill.ui.common.TextFieldUtils;

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
        /** Lock an unlocked collection, or remove the lock from a locked one — which of the two is
         *  decided by the collection's own state, so there is only one entry. */
        void onToggleLock();
    }

    private CollectionDialogs() {}

    /** The two ways a note gets into a collection. */
    public interface AddNoteListener {
        void onNewNote();
        void onExistingNote();
    }

    /**
     * Asks which of the two the user meant, for the collection screen's "+" and for the button on
     * its empty state — both of which used to be halves of a split button that named neither.
     */
    public static void showAddNoteDialog(Context context, AddNoteListener listener) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_add_note_title)
                .setItems(new String[]{
                        context.getString(R.string.action_add_note_new),
                        context.getString(R.string.action_add_note_existing)
                }, (dialog, which) -> {
                    if (which == 0) listener.onNewNote();
                    else listener.onExistingNote();
                })
                .show();
    }

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

    /**
     * Dialog setView() places content flush against the dialog edges; the field needs breathing
     * room. Moved to {@link TextFieldUtils} once the Profile screen — in another package — needed
     * the same wrapper; kept here as a delegate so the call sites in this package read unchanged.
     */
    static LinearLayout inset(Context context, TextInputLayout field) {
        return TextFieldUtils.inset(context, field);
    }

    public static void showManageDialog(Context context, Collection collection, ManageListener listener) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(collection.name)
                .setItems(new String[]{
                        context.getString(R.string.action_rename),
                        CollectionLockFlow.toggleLabel(context, collection),
                        context.getString(R.string.action_delete)
                }, (dialog, which) -> {
                    if (which == 0) listener.onRename();
                    else if (which == 1) listener.onToggleLock();
                    else listener.onDelete();
                })
                .show();
    }

    /** @param excludeCollectionId a collection id to omit from the list (e.g. the one currently open), or null. */
    /**
     * Picks where a note should go.
     *
     * <p>Locked destinations are labelled, and confirm before they are used. Moving a note into a
     * locked collection encrypts it, and that costs its flashcards: the cards keep the question and
     * answer as their own plaintext columns, so leaving them behind would keep a readable copy of
     * the note in a table the lock doesn't reach — the same reason locking a whole collection
     * removes them. Locking says so in its confirmation; this path used to do the identical thing
     * in silence, so a deck made a minute earlier simply vanished.
     */
    public static void showAssignCollectionDialog(Context context, List<Collection> collections,
                                                   String excludeCollectionId, OnCollectionPicked callback) {
        List<Collection> options = new ArrayList<>();
        for (Collection c : collections) {
            if (!c.id.equals(excludeCollectionId)) options.add(c);
        }

        String[] items = new String[options.size()];
        for (int i = 0; i < options.size(); i++) {
            Collection option = options.get(i);
            items[i] = option.biometricLocked
                    ? context.getString(R.string.collection_locked_option, option.name)
                    : option.name;
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.action_move_to_collection)
                .setItems(items, (dialog, which) -> {
                    Collection picked = options.get(which);
                    if (picked.biometricLocked) confirmMoveIntoLocked(context, picked, callback);
                    else callback.onPicked(picked.id);
                })
                .show();
    }

    private static void confirmMoveIntoLocked(Context context, Collection destination,
                                              OnCollectionPicked callback) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.move_into_locked_title, destination.name))
                .setMessage(R.string.move_into_locked_message)
                .setPositiveButton(R.string.action_move, (d, w) -> callback.onPicked(destination.id))
                .setNegativeButton(R.string.action_cancel, null)
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
