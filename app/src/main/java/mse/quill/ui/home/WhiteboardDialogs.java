package mse.quill.ui.home;

import android.content.Context;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import mse.quill.R;
import mse.quill.data.model.Whiteboard;
import mse.quill.util.NoteDisplayUtils;
import mse.quill.util.TextFieldUtils;

/**
 * Name / rename / delete dialogs for whiteboards, mirroring {@link CollectionDialogs} — same
 * MaterialAlertDialogBuilder idiom, and the field inset helper is shared with it rather than
 * duplicated.
 */
public final class WhiteboardDialogs {

    public interface OnTextPicked { void onPicked(String text); }

    public interface ManageListener {
        void onRename();
        void onDelete();
    }

    private WhiteboardDialogs() {}


    public static void showRenameDialog(Context context, String currentTitle, OnTextPicked callback) {
        TextInputLayout nameField = TextFieldUtils.outlinedField(context, R.string.whiteboard_name_hint);
        String prefill = currentTitle == null ? "" : currentTitle;
        nameField.getEditText().setText(prefill);
        nameField.getEditText().setSelection(prefill.length());

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_rename_whiteboard_title)
                .setView(CollectionDialogs.inset(context, nameField))
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String name = nameField.getEditText().getText().toString().trim();
                    if (!name.isEmpty()) callback.onPicked(name);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    public static void showManageDialog(Context context, Whiteboard whiteboard, ManageListener listener) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(NoteDisplayUtils.resolveWhiteboardTitle(context, whiteboard))
                .setItems(new String[]{
                        context.getString(R.string.action_rename),
                        context.getString(R.string.action_delete)
                }, (dialog, which) -> {
                    if (which == 0) listener.onRename();
                    else listener.onDelete();
                })
                .show();
    }

    /** Deletion is permanent (there is no whiteboard trash), so it always confirms. */
    public static void showDeleteConfirmation(Context context, Whiteboard whiteboard, Runnable onConfirmed) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.delete_whiteboard_title_format,
                        NoteDisplayUtils.resolveWhiteboardTitle(context, whiteboard)))
                .setMessage(R.string.delete_whiteboard_message)
                .setPositiveButton(R.string.action_delete, (d, w) -> onConfirmed.run())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
