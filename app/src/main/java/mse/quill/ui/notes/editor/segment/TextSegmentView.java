package mse.quill.ui.notes.editor.segment;

import android.content.Context;
import android.text.Spannable;
import android.view.ViewGroup;
import android.widget.EditText;

import mse.quill.R;
import mse.quill.ui.notes.editor.RichTextField;
import mse.quill.data.model.NoteSegment;

/**
 * A run of body prose. Thin wrapper around a single {@link RichTextField} — all the formatting
 * behaviour lives there so a Q&amp;A segment can reuse it for its two fields.
 */
public class TextSegmentView extends BaseSegmentView {

    private final RichTextField field;

    public TextSegmentView(Context context) {
        super(context);
        setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        field = new RichTextField(context);
        field.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        field.setListener(new RichTextField.Listener() {
            @Override public void onContentChanged() {
                if (callback != null) callback.onContentChanged();
            }
            @Override public void onSelectionChanged() {
                if (callback != null) callback.onSelectionChanged();
            }
            @Override public boolean onBackspaceAtStart() {
                if (callback == null) return false;
                callback.onRequestMergeWithPrevious(TextSegmentView.this);
                return true;
            }
        });
        addView(field);
    }

    public RichTextField getField() { return field; }

    /** Only the note's last text segment prompts. The empty segments a block insert leaves behind
     *  mid-note are structural gaps, not invitations to write — repeating the hint down the page
     *  reads as clutter. */
    public void setHintVisible(boolean visible) {
        field.setHint(visible ? getContext().getString(R.string.note_body_hint) : null);
    }

    /** Kept for callers that only need the widget (e.g. cursor position when splitting). */
    public EditText getEditText() { return field; }

    public void setText(Spannable text) { field.setRichText(text); }
    public Spannable getText() { return field.getRichText(); }

    public void focusAtStart() { field.focusAtStart(); }
    public void focusAtEnd() { field.focusAtEnd(); }
    public void focusAt(int position) { field.focusAt(position); }

    public void applyBold() { field.applyBold(); }
    public void applyItalic() { field.applyItalic(); }
    public void applyUnderline() { field.applyUnderline(); }
    public void applyHeading(int level) { field.applyHeading(level); }
    public void applyBulletList() { field.applyBulletList(); }

    public boolean isBoldActive() { return field.isBoldActive(); }
    public boolean isItalicActive() { return field.isItalicActive(); }
    public boolean isUnderlineActive() { return field.isUnderlineActive(); }
    public int currentHeadingLevel() { return field.currentHeadingLevel(); }
    public boolean isBulletActive() { return field.isBulletActive(); }
    public void clearBulletContinuation() { field.clearBulletContinuation(); }

    @Override
    public int getSegmentType() { return NoteSegment.TYPE_TEXT; }

    @Override
    public Object getSegmentData() { return field.getText(); }
}
