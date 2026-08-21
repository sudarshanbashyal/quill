package mse.quill.ui.quiz;

import android.content.Context;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

import mse.quill.R;
import mse.quill.data.model.QuizAttempt;
import mse.quill.util.CardStyles;

/**
 * A quiz's history: one row per sitting, newest first.
 *
 * <p>Abandoned attempts are listed alongside completed ones rather than filtered out. Half the
 * point of a history is spotting the quiz you keep walking out of, and a list that quietly dropped
 * those would show a run of good scores and nothing else.
 *
 * <p>Rows are built in code — see {@code ui.home.NoteRowView} for why inflation isn't used here.
 */
public class QuizAttemptsAdapter extends RecyclerView.Adapter<QuizAttemptsAdapter.AttemptHolder> {

    /** Tapping a row reopens that sitting's marked paper. */
    public interface Listener { void onAttemptClicked(QuizAttempt attempt); }

    private final Listener listener;
    private List<QuizAttempt> attempts = new ArrayList<>();

    public QuizAttemptsAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<QuizAttempt> attempts) {
        this.attempts = attempts;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AttemptHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new AttemptHolder(build(parent.getContext()));
    }

    @Override
    public void onBindViewHolder(@NonNull AttemptHolder holder, int position) {
        QuizAttempt attempt = attempts.get(position);
        holder.bind(attempt);
        holder.itemView.setOnClickListener(v -> listener.onAttemptClicked(attempt));
    }

    @Override
    public int getItemCount() {
        return attempts.size();
    }

    private static Views build(Context context) {
        int spacingMd = CardStyles.dimen(context, R.dimen.spacing_md);
        int spacingSm = CardStyles.dimen(context, R.dimen.spacing_sm);
        int spacingXs = CardStyles.dimen(context, R.dimen.spacing_xs);

        MaterialCardView card = new MaterialCardView(context);
        RecyclerView.LayoutParams cardParams = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(spacingMd, spacingXs, spacingMd, spacingXs);
        card.setLayoutParams(cardParams);
        CardStyles.applyFlatCardStyle(card, R.dimen.note_row_corner_radius);
        card.setCardBackgroundColor(context.getColor(R.color.surface_container));
        // Nothing opens from a past attempt, so it shouldn't answer a press like something that does.
        card.setClickable(false);
        card.setFocusable(false);

        LinearLayout row = new LinearLayout(context);
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(spacingSm, spacingSm, spacingSm, spacingSm);
        card.addView(row);

        LinearLayout column = new LinearLayout(context);
        column.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(spacingSm, 0, 0, 0);
        row.addView(column);

        TextView status = new TextView(context);
        status.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setTypeface(status.getTypeface(), Typeface.BOLD);
        column.addView(status);

        TextView when = new TextView(context);
        when.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        when.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        column.addView(when);

        TextView score = new TextView(context);
        score.setGravity(Gravity.END);
        score.setPadding(spacingSm, 0, spacingMd - spacingSm, 0);
        score.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        score.setTypeface(score.getTypeface(), Typeface.BOLD);
        row.addView(score);

        return new Views(card, status, when, score);
    }

    static final class Views {
        final View root;
        final TextView status;
        final TextView when;
        final TextView score;

        Views(View root, TextView status, TextView when, TextView score) {
            this.root = root;
            this.status = status;
            this.when = when;
            this.score = score;
        }
    }

    static class AttemptHolder extends RecyclerView.ViewHolder {

        private final Views views;

        AttemptHolder(Views views) {
            super(views.root);
            this.views = views;
        }

        void bind(QuizAttempt attempt) {
            Context context = itemView.getContext();
            boolean completed = attempt.isCompleted();

            views.status.setText(completed
                    ? context.getString(R.string.quiz_attempt_completed)
                    : context.getString(R.string.quiz_attempt_abandoned_format,
                            attempt.answered, attempt.total));
            // An absolute date and time, not "3 days ago": a history is read to compare sittings
            // with each other, and relative spans stop being comparable past a day or two.
            views.when.setText(DateFormat.getMediumDateFormat(context)
                    .format(attempt.startedAt)
                    + " · "
                    + DateFormat.getTimeFormat(context).format(attempt.startedAt));

            views.score.setText(context.getString(
                    R.string.quiz_attempt_score_format, attempt.score, attempt.total));
            // An abandoned score is greyed: it's out of the questions that were reached, not out of
            // the quiz, and printing it as boldly as a finished one would invite comparing the two.
            views.score.setTextColor(ContextCompat.getColor(context,
                    completed ? R.color.text_primary : R.color.text_secondary));
        }
    }
}
