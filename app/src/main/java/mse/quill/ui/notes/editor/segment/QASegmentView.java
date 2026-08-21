package mse.quill.ui.notes.editor.segment;

import android.content.Context;
import android.text.Spannable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

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

        // The question shares its line with the block's delete control. Top-aligned rather than
        // centred so the cross stays level with the first line of a question that wraps.
        LinearLayout questionRow = new LinearLayout(context);
        questionRow.setOrientation(HORIZONTAL);
        questionRow.setGravity(Gravity.TOP);
        addView(questionRow, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        questionField = buildField(context);
        questionField.setHint(R.string.qa_question_hint);
        questionField.setTextSize(
                TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.qa_question_text_size));
        questionField.setTextColor(ContextCompat.getColor(context, R.color.qa_question_text));
        questionRow.addView(questionField, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        questionRow.addView(buildDeleteButton(context));

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

    }

    /**
     * The cross in the block's corner.
     *
     * <p>This used to be a long-press on the card, and in practice you could hardly ever land it:
     * the card is almost entirely covered by two {@code EditText}s whose own long-press is text
     * selection, so the gesture nearly always produced a copy/paste menu instead. A visible control
     * of its own is both easier to hit and easier to find.
     */
    private ImageView buildDeleteButton(Context context) {
        ImageView delete = new ImageView(context);
        int size = dimen(R.dimen.qa_delete_touch_target);
        int padding = (size - dimen(R.dimen.qa_delete_icon)) / 2;
        delete.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        delete.setPadding(padding, padding, padding, padding);
        delete.setImageResource(R.drawable.ic_clear);
        ImageViewCompat.setImageTintList(delete, android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.qa_question_text)));
        delete.setContentDescription(context.getString(R.string.action_delete_qa_block));
        delete.setBackground(borderlessRipple(context));
        delete.setOnClickListener(v -> requestDelete());
        return delete;
    }

    private static android.graphics.drawable.Drawable borderlessRipple(Context context) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, value, true);
        return ContextCompat.getDrawable(context, value.resourceId);
    }

    /**
     * Removes the block, asking first only when there is something to lose.
     *
     * <p>An empty block is nearly always one that was just inserted by mistake, and putting a
     * dialog in front of undoing that mistake is the sort of confirmation nobody reads. A block
     * with a question or an answer in it gets the dialog, because deleting it is the only way the
     * note loses that text.
     */
    private void requestDelete() {
        if (isEmpty()) {
            if (callback != null) callback.onRequestDelete(this);
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.delete_qa_title)
                .setMessage(R.string.delete_qa_message)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    if (callback != null) callback.onRequestDelete(this);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private boolean isEmpty() {
        return TextUtils.isEmpty(questionField.getText().toString().trim())
                && TextUtils.isEmpty(answerField.getText().toString().trim());
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
                return onBackspaceAtStartOf(field);
            }
        });
        return field;
    }

    /**
     * Backspace with the caret at the very start of one of the two fields.
     *
     * <p>From the answer it steps back into the question, so the block behaves like the one field
     * it looks like rather than trapping the caret behind an invisible boundary.
     *
     * <p>From the question it deletes the block — the same route as the cross, confirmation and
     * all. Still deliberately not a merge-with-previous: dissolving a question into the prose above
     * it would leave the answer stranded, so the choice is "this block or nothing", and backspace
     * at the top of it can only mean the former.
     */
    private boolean onBackspaceAtStartOf(RichTextField field) {
        if (field == answerField) {
            questionField.focusAtEnd();
            return true;
        }
        requestDelete();
        return true;
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
