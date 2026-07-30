package mse.quill.ui.flashcards;

import android.content.Context;
import android.text.TextUtils;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import mse.quill.R;

/**
 * The one confirmation both delete paths share — the decks list and the review screen — so the
 * warning about losing review progress can't drift between them.
 */
final class DeleteFlashcardsDialog {

    private DeleteFlashcardsDialog() {}

    static void show(Context context, String noteTitle, Runnable onConfirmed) {
        String title = TextUtils.isEmpty(noteTitle)
                ? context.getString(R.string.delete_flashcards_title)
                : context.getString(R.string.delete_flashcards_title_format, noteTitle);

        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(R.string.delete_flashcards_message)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> onConfirmed.run())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
