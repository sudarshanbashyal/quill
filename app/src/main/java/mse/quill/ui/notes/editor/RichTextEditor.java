package mse.quill.ui.notes.editor;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spannable;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.widget.EditText;

public class RichTextEditor {

    private final EditText editText;
    private boolean isBoldActive = false;
    private boolean isItalicActive = false;
    private boolean isUnderlineActive = false;

    public RichTextEditor(EditText editText) {
        this.editText = editText;
    }

    public void toggleBold() {
        Editable text = editText.getText();
        int start = editText.getSelectionStart();
        int end = editText.getSelectionEnd();

        if (start != end) {
            // Text is selected — apply/remove to selection only
            StyleSpan[] spans = text.getSpans(start, end, StyleSpan.class);
            boolean alreadyBold = false;
            for (StyleSpan span : spans) {
                if (span.getStyle() == Typeface.BOLD) {
                    alreadyBold = true;
                    text.removeSpan(span);
                }
            }
            if (!alreadyBold) {
                text.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } else {
            // No selection — toggle bold mode for future typing
            isBoldActive = !isBoldActive;
            // Visual feedback — change button appearance
            // updateToolbarButtonStates();
        }
    }
    public void toggleItalic() {
        Editable text = editText.getText();
        int start = editText.getSelectionStart();
        int end = editText.getSelectionEnd();

        if(start!=end){
            StyleSpan[] spans = text.getSpans(start, end, StyleSpan.class);
            boolean alreadyItalic = false;
            for (StyleSpan span : spans) {
                if (span.getStyle() == Typeface.ITALIC) {
                    alreadyItalic = true;
                    text.removeSpan(span);
                }
            }
            if (!alreadyItalic) {
                text.setSpan(
                        new StyleSpan(Typeface.ITALIC),
                        start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            }
        }else{
            // No selection — toggle bold mode for future typing
            isItalicActive = !isItalicActive;
            // Visual feedback — change button appearance
            //updateToolbarButtonStates();
        }
    }
    public void toggleUnderline() {
        Editable text = editText.getText();
        int start = editText.getSelectionStart();
        int end = editText.getSelectionEnd();

        if(start!=end) {
            UnderlineSpan[] spans = text.getSpans(start, end, UnderlineSpan.class);
            if (spans.length > 0) {
                for (UnderlineSpan span : spans) text.removeSpan(span);
            } else {
                text.setSpan(new UnderlineSpan(), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }else {
            // No selection — toggle bold mode for future typing
            isUnderlineActive = !isUnderlineActive;
            // Visual feedback — change button appearance
            //updateToolbarButtonStates();
        }
    }

    public boolean isBoldActive() { return isBoldActive; }
    public boolean isItalicActive() { return isItalicActive; }
    public boolean isUnderlineActive() { return isUnderlineActive; }

    // Apply active formats to newly typed character
    public void applyActiveFormats(Editable s) {
        int cursor = editText.getSelectionStart();
        if (cursor > 0) {
            if (isBoldActive)
                s.setSpan(new StyleSpan(Typeface.BOLD), cursor - 1, cursor,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (isItalicActive)
                s.setSpan(new StyleSpan(Typeface.ITALIC), cursor - 1, cursor,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (isUnderlineActive)
                s.setSpan(new UnderlineSpan(), cursor - 1, cursor,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // Serialise content for saving
    public byte[] serialiseContent() {
        // TODO: convert Spannable → byte[]
        return editText.getText().toString().getBytes();
    }
}