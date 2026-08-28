package mse.quill.ui.home;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import mse.quill.R;
import mse.quill.data.model.Whiteboard;
import mse.quill.util.NoteDisplayUtils;
import mse.quill.ui.common.TextFieldUtils;

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

    /**
     * Deletion is permanent (there is no whiteboard trash), so it always confirms.
     *
     * <p>{@code embeddingNotes} is how many notes have this board in them. When it is more than
     * none, the message gains a second line in the danger colour: the board is about to vanish out
     * of the middle of writing the user is not currently looking at, and a confirmation that only
     * mentions the drawing is not describing what the button does.
     */
    public static void showDeleteConfirmation(Context context, Whiteboard whiteboard,
                                              int embeddingNotes, Runnable onConfirmed) {
        CharSequence message = context.getString(R.string.delete_whiteboard_message);
        if (embeddingNotes > 0) {
            String warning = context.getResources().getQuantityString(
                    R.plurals.delete_whiteboard_embedded_warning, embeddingNotes, embeddingNotes);
            SpannableStringBuilder body = new SpannableStringBuilder(message);
            body.append("\n\n");
            int start = body.length();
            body.append(warning);
            body.setSpan(new ForegroundColorSpan(context.getColor(R.color.danger)),
                    start, body.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            body.setSpan(new StyleSpan(Typeface.BOLD),
                    start, body.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            message = body;
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.delete_whiteboard_title_format,
                        NoteDisplayUtils.resolveWhiteboardTitle(context, whiteboard)))
                .setMessage(message)
                .setPositiveButton(R.string.action_delete, (d, w) -> onConfirmed.run())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
