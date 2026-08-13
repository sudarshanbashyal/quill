package mse.quill.ui.quiz;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import mse.quill.R;
import mse.quill.util.NoteDisplayUtils;
import mse.quill.util.RelativeTime;
import mse.quill.data.model.Quiz;

/** The quizzes list: one row per note that has been turned into a quiz. */
public class QuizzesAdapter extends RecyclerView.Adapter<QuizzesAdapter.QuizHolder> {

    public interface Listener {
        void onQuizClicked(Quiz quiz);
        void onDeleteClicked(Quiz quiz);
    }

    private final Listener listener;
    private List<Quiz> quizzes = new ArrayList<>();

    public QuizzesAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Quiz> quizzes) {
        this.quizzes = quizzes;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public QuizHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new QuizHolder(QuizRowView.build(parent.getContext()));
    }

    @Override
    public void onBindViewHolder(@NonNull QuizHolder holder, int position) {
        holder.bind(quizzes.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return quizzes.size();
    }

    static class QuizHolder extends RecyclerView.ViewHolder {

        private final QuizRowView.Views views;

        QuizHolder(QuizRowView.Views views) {
            super(views.root);
            this.views = views;
        }

        void bind(Quiz quiz, Listener listener) {
            Context context = itemView.getContext();
            boolean scored = quiz.bestPercent != null;

            // An untaken quiz gets a dash rather than 0% — it hasn't scored badly, it hasn't
            // scored, and a purple 0 badge would read as a result.
            views.badge.setText(scored
                    ? context.getString(R.string.quiz_attempt_percent_format, quiz.bestPercent)
                    : "—");
            views.badge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
                    context, scored ? R.color.brand_purple : R.color.divider)));
            views.badge.setTextColor(ContextCompat.getColor(
                    context, scored ? R.color.text_on_brand : R.color.text_secondary));

            views.title.setText(NoteDisplayUtils.resolveTitle(
                    context, quiz.noteTitle, quiz.noteCreatedAt));
            views.detail.setText(detailLine(context, quiz));

            // Nothing to date a quiz by until it has been opened once, and an empty line still
            // takes a line's worth of row.
            boolean taken = quiz.lastAttemptAt != null;
            views.meta.setVisibility(taken ? View.VISIBLE : View.GONE);
            if (taken) {
                views.meta.setText(context.getString(R.string.quiz_row_last_taken_format,
                        RelativeTime.past(context, quiz.lastAttemptAt)));
            }

            views.root.setOnClickListener(v -> listener.onQuizClicked(quiz));
            views.deleteButton.setOnClickListener(v -> listener.onDeleteClicked(quiz));
        }

        /**
         * Counts completed attempts, and distinguishes the two ways of having none: never opened,
         * or opened and walked out of. The second has a "last taken" line underneath it, which
         * beside "Not taken yet" would contradict itself.
         */
        private static String detailLine(Context context, Quiz quiz) {
            if (quiz.attempts > 0) {
                return context.getResources().getQuantityString(
                        R.plurals.quiz_row_attempts, quiz.attempts, quiz.attempts);
            }
            return quiz.lastAttemptAt == null
                    ? context.getString(R.string.quiz_row_never_taken)
                    : context.getString(R.string.quiz_row_not_completed);
        }
    }
}
