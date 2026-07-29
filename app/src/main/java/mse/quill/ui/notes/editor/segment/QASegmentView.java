package mse.quill.ui.notes.editor.segment;

import android.content.Context;
import android.text.Spannable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import mse.quill.R;
import mse.quill.ui.notes.editor.RichTextField;
import mse.quill.ui.notes.editor.model.NoteSegment;

/**
 * A question/answer block, per the MSE Figma's "QA" frame: a tonal rounded card holding a muted
 * question line above an answer indented behind a green vertical rule.
 *
 * <p>Both regions are full {@link RichTextField}s, so bold/italic/underline and bullets work in
 * either — but with headings switched off. Blocks (headings, images, audio) would fight the point
 * of the block: it's one atomic question and one atomic answer, destined to become a flashcard,
 * not a place to nest document structure. The toolbar greys those controls out by asking the
 * focused field what it allows, so the rule holds wherever the caret is rather than depending on
 * the toolbar knowing about Q&amp;A at all.
 */
public class QASegmentView extends BaseSegmentView {

    private final RichTextField questionField;
    private final RichTextField answerField;

    public QASegmentView(Context context, String segmentId) {
        super(context, segmentId);

        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.bg_qa_block);
        int padding = dimen(R.dimen.qa_block_padding);
        setPadding(padding, padding, padding, padding);

        LayoutParams params = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int spacing = dimen(R.dimen.qa_block_spacing);
        params.topMargin = spacing;
        params.bottomMargin = spacing;
        setLayoutParams(params);

        questionField = buildField(context);
        questionField.setHint(R.string.qa_question_hint);
        questionField.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.qa_question_text_size));
        questionField.setTextColor(ContextCompat.getColor(context, R.color.qa_question_text));
        addView(questionField, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The rule is MATCH_PARENT inside a wrap_content row, so it always spans exactly the
        // answer's height however many lines it grows to.
        LinearLayout answerRow = new LinearLayout(context);
        answerRow.setOrientation(HORIZONTAL);
        addView(answerRow, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View rule = new View(context);
        rule.setBackgroundColor(ContextCompat.getColor(context, R.color.qa_answer_rule));
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(
                dimen(R.dimen.qa_rule_width), ViewGroup.LayoutParams.MATCH_PARENT);
        ruleParams.setMarginEnd(dimen(R.dimen.qa_rule_gap));
        answerRow.addView(rule, ruleParams);

        answerField = buildField(context);
        answerField.setHint(R.string.qa_answer_hint);
        answerRow.addView(answerField, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Long-press the card itself — its padding, the rule gutter, the space beside the text —
        // rather than the fields, whose own long-press has to stay text selection (selecting is
        // how bold and italic get applied to part of an answer).
        setOnLongClickListener(v -> {
            showDeleteConfirmation();
            return true;
        });
    }

    private void showDeleteConfirmation() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.delete_qa_title)
                .setMessage(R.string.delete_qa_message)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    if (callback != null) callback.onRequestDelete(this);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private RichTextField buildField(Context context) {
        RichTextField field = new RichTextField(context);
        field.setHeadingsAllowed(false);
        field.setListener(new RichTextField.Listener() {
            @Override public void onContentChanged() {
                if (callback != null) callback.onContentChanged();
            }
            @Override public void onSelectionChanged() {
                if (callback != null) callback.onSelectionChanged();
            }
            @Override public boolean onBackspaceAtStart() {
                // Deliberately not a merge-with-previous: backspacing out of a question would
                // dissolve the block into the prose above it, losing the answer's association.
                return false;
            }
        });
        return field;
    }

    private int dimen(int res) {
        return getResources().getDimensionPixelSize(res);
    }

    public RichTextField getQuestionField() { return questionField; }
    public RichTextField getAnswerField() { return answerField; }

    public void setContent(Spannable question, Spannable answer) {
        questionField.setRichText(question);
        answerField.setRichText(answer);
    }

    public Spannable getQuestion() { return questionField.getRichText(); }
    public Spannable getAnswer() { return answerField.getRichText(); }

    public void focusQuestion() { questionField.focusAtStart(); }

    @Override
    public int getSegmentType() { return NoteSegment.TYPE_QA; }

    @Override
    public Object getSegmentData() { return questionField.getText(); }
}
