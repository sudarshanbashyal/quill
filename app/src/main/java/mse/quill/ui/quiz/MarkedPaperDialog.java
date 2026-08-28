package mse.quill.ui.quiz;

import android.content.Context;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

import mse.quill.R;
import mse.quill.data.model.QuizAttempt;
import mse.quill.study.quiz.QuizSession;

/**
 * A past attempt's marked paper: every question as it was asked, with the option that was chosen.
 *
 * <p>The same {@link QuizResultsAdapter} the end of a live run uses, so a paper looks the same
 * whether it is two seconds or two weeks old — and so there is only one place that decides what
 * "you picked this, the answer was that" looks like.
 *
 * <p>Scrolls inside the dialog rather than stretching it: a twenty-question quiz would otherwise
 * make a dialog taller than the screen, and the header that says which attempt this is would be
 * the first thing pushed off it.
 */
final class MarkedPaperDialog {

    private MarkedPaperDialog() {}

    static void show(Context context, QuizAttempt attempt, List<QuizSession.Result> results) {
        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        int padding = context.getResources().getDimensionPixelSize(R.dimen.spacing_md);
        list.setPadding(padding, padding, padding, 0);
        list.setClipToPadding(false);

        QuizResultsAdapter adapter = new QuizResultsAdapter();
        adapter.submit(results);
        list.setAdapter(adapter);

        // The score as it was recorded, not recounted from the rows: an attempt walked out of has
        // fewer answers than questions, and the two numbers should agree with the row that was
        // tapped to get here.
        String title = context.getString(R.string.quiz_marked_paper_title,
                attempt.score, results.size());

        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(list)
                .setPositiveButton(R.string.action_done, null)
                .show();
    }
}
