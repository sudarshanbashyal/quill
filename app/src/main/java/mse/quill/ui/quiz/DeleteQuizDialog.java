package mse.quill.ui.quiz;

import android.content.Context;
import android.text.TextUtils;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import mse.quill.R;

/**
 * The one confirmation both delete paths share — the quizzes list and a quiz's own screen — so the
 * warning about losing every past score can't drift between them.
 */
final class DeleteQuizDialog {

    private DeleteQuizDialog() {}

    static void show(Context context, String noteTitle, Runnable onConfirmed) {
        String title = TextUtils.isEmpty(noteTitle)
                ? context.getString(R.string.delete_quiz_title)
                : context.getString(R.string.delete_quiz_title_format, noteTitle);

        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(R.string.delete_quiz_message)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> onConfirmed.run())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
