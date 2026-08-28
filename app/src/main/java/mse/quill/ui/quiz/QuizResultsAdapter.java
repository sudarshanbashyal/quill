package mse.quill.ui.quiz;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import mse.quill.R;
import mse.quill.data.serialization.MarkdownSerializer;
import mse.quill.ui.common.CardStyles;

/**
 * The marked paper shown at the end of a quiz: every question with what was picked and what was
 * right.
 *
 * <p>Shown here rather than after each question, and that's the design: a quiz is a measurement, and
 * grading each answer as it's given turns it into practice — the user starts learning from question
 * three onwards, which is a different activity with a different (inflated) score.
 *
 * <p>The correct answer is stated on every row, including the ones that were right. Only marking
 * the failures makes the list a scolding; repeating the answer makes it revision.
 */
public class QuizResultsAdapter extends RecyclerView.Adapter<QuizResultsAdapter.ResultHolder> {

    private List<QuizSession.Result> results = new ArrayList<>();

    public void submit(List<QuizSession.Result> results) {
        this.results = results;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ResultHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ResultHolder(build(parent.getContext()));
    }

    @Override
    public void onBindViewHolder(@NonNull ResultHolder holder, int position) {
        holder.bind(results.get(position), position + 1);
    }

    @Override
    public int getItemCount() {
        return results.size();
    }

    /**
     * A vertical rule, then the question and the two answer lines — the same "rule owns the text
     * beside it" shape a Q&amp;A block uses in the note itself, so a result reads as the block it
     * came from.
     */
    private static Views build(Context context) {
        int spacingMd = CardStyles.dimen(context, R.dimen.spacing_md);
        int spacingSm = CardStyles.dimen(context, R.dimen.spacing_sm);
        int spacingXs = CardStyles.dimen(context, R.dimen.spacing_xs);

        LinearLayout row = new LinearLayout(context);
        RecyclerView.LayoutParams rowParams = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, spacingXs, 0, spacingSm);
        row.setLayoutParams(rowParams);
        row.setOrientation(LinearLayout.HORIZONTAL);

        View rule = new View(context);
        rule.setLayoutParams(new LinearLayout.LayoutParams(
                CardStyles.dimen(context, R.dimen.quiz_result_rule_width),
                ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(rule);

        LinearLayout column = new LinearLayout(context);
        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        columnParams.setMarginStart(spacingMd - spacingXs);
        column.setLayoutParams(columnParams);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, spacingXs, 0, spacingSm);
        row.addView(column);

        TextView prompt = new TextView(context);
        prompt.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        prompt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        prompt.setTypeface(prompt.getTypeface(), Typeface.BOLD);
        column.addView(prompt);

        TextView given = new TextView(context);
        LinearLayout.LayoutParams givenParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        givenParams.topMargin = spacingXs;
        given.setLayoutParams(givenParams);
        given.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        column.addView(given);

        TextView correct = new TextView(context);
        correct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        correct.setTextColor(ContextCompat.getColor(context, R.color.answer_correct));
        column.addView(correct);

        return new Views(row, rule, prompt, given, correct);
    }

    static final class Views {
        final View root;
        final View rule;
        final TextView prompt;
        final TextView given;
        final TextView correct;

        Views(View root, View rule, TextView prompt, TextView given, TextView correct) {
            this.root = root;
            this.rule = rule;
            this.prompt = prompt;
            this.given = given;
            this.correct = correct;
        }
    }

    static class ResultHolder extends RecyclerView.ViewHolder {

        private final Views views;

        ResultHolder(Views views) {
            super(views.root);
            this.views = views;
        }

        void bind(QuizSession.Result result, int number) {
            Context context = itemView.getContext();
            boolean right = result.wasCorrect();
            int accent = ContextCompat.getColor(context,
                    right ? R.color.answer_correct : R.color.answer_incorrect);

            views.rule.setBackgroundColor(accent);
            // Concatenated rather than formatted, here and below, so the Markdown's spans survive
            // — String.format would flatten a bolded term back to plain text.
            views.prompt.setText(TextUtils.concat(number + ". ",
                    MarkdownSerializer.fromMarkdown(result.question.prompt)));

            if (result.wasAnswered()) {
                views.given.setText(TextUtils.expandTemplate(
                        context.getText(R.string.quiz_results_your_answer_format),
                        MarkdownSerializer.fromMarkdown(result.selectedOption())));
            } else {
                views.given.setText(R.string.quiz_results_no_answer);
            }
            views.given.setTextColor(right
                    ? ContextCompat.getColor(context, R.color.text_secondary) : accent);

            // The correct answer is redundant on a right answer, so it's dropped there rather than
            // repeating the line above it word for word.
            views.correct.setVisibility(right ? View.GONE : View.VISIBLE);
            views.correct.setText(TextUtils.expandTemplate(
                    context.getText(R.string.quiz_results_correct_format),
                    MarkdownSerializer.fromMarkdown(result.question.correctOption())));
        }
    }
}
