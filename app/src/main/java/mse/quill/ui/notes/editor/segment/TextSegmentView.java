package mse.quill.ui.notes.editor.segment;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.EditText;

import mse.quill.ui.notes.editor.model.NoteSegment;

public class TextSegmentView extends BaseSegmentView {

    private final EditText editText;
    private boolean isBoldActive = false;
    private boolean isItalicActive = false;
    private boolean isUnderlineActive = false;

    public TextSegmentView(Context context) {
        super(context);
        setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        editText = new EditText(context);
        editText.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        editText.setBackground(null);
        editText.setTextSize(16);
        editText.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT |
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        );
        editText.setHint("Write something...");

        addView(editText);
        setupKeyListener();
        setupTextWatcher();
    }

    public void setText(Spannable text) {
        editText.setText(text);
    }

    public Spannable getText() {
        return (Spannable) editText.getText();
    }

    public void focusAtEnd() {
        editText.requestFocus();
        editText.setSelection(editText.getText().length());
    }

    // In TextSegmentView.java
    public EditText getEditText() { return editText; }

    public void focusAt(int position) {
        editText.requestFocus();
        editText.setSelection(Math.min(position, editText.getText().length()));
    }

    public void focusAtStart() {
        editText.requestFocus();
        editText.setSelection(0);
    }

    public void applyBold() { applyStyle(Typeface.BOLD); }
    public void applyItalic() { applyStyle(Typeface.ITALIC); }
    public void applyUnderline() { applyStyle(-1); } // -1 signals underline

    public boolean isBoldActive() { return isBoldActive; }
    public boolean isItalicActive() { return isItalicActive; }
    public boolean isUnderlineActive() { return isUnderlineActive; }

    private void applyStyle(int style) {
        Editable text = editText.getText();
        int start = editText.getSelectionStart();
        int end = editText.getSelectionEnd();

        if (start == end) {
            // No selection — toggle active mode for future typing
            if (style == Typeface.BOLD) {
                isBoldActive = !isBoldActive;
            } else if (style == Typeface.ITALIC) {
                isItalicActive = !isItalicActive;
            } else if (style == -1) { // underline
                isUnderlineActive = !isUnderlineActive;
            }
        } else {
            // Text is selected — apply style to selection
            if (style == Typeface.BOLD) {
                toggleStyleOnSelection(text, start, end, Typeface.BOLD, StyleSpan.class);
            } else if (style == Typeface.ITALIC) {
                toggleStyleOnSelection(text, start, end, Typeface.ITALIC, StyleSpan.class);
            } else if (style == -1) { // underline
                toggleUnderlineOnSelection(text, start, end);
            }
        }
    }

    private void toggleStyleOnSelection(Editable text, int start, int end, int typeface, Class<?> spanType) {
        StyleSpan[] spans = text.getSpans(start, end, StyleSpan.class);
        boolean alreadyHasStyle = false;
        for (StyleSpan span : spans) {
            if (span.getStyle() == typeface) {
                alreadyHasStyle = true;
                text.removeSpan(span);
            }
        }
        if (!alreadyHasStyle) {
            text.setSpan(new StyleSpan(typeface), start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void toggleUnderlineOnSelection(Editable text, int start, int end) {
        UnderlineSpan[] spans = text.getSpans(start, end, UnderlineSpan.class);
        if (spans.length > 0) {
            for (UnderlineSpan span : spans) {
                text.removeSpan(span);
            }
        } else {
            text.setSpan(new UnderlineSpan(), start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // Detect backspace at position 0 — merge with previous segment
    private void setupKeyListener() {
        editText.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DEL
                    && event.getAction() == KeyEvent.ACTION_DOWN
                    && editText.getSelectionStart() == 0
                    && editText.getSelectionEnd() == 0
                    && callback != null) {
                callback.onRequestMergeWithPrevious(this);
                return true;
            }
            return false;
        });
    }

    private void setupTextWatcher() {
        editText.addTextChangedListener(new TextWatcher() {
            private int charCountAdded = 0;

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                charCountAdded = count;
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Apply active formatting to newly typed character(s)
                int cursor = editText.getSelectionStart();
                if (cursor > 0 && charCountAdded > 0) {
                    int spanStart = cursor - charCountAdded;
                    if (isBoldActive) {
                        s.setSpan(new StyleSpan(Typeface.BOLD), spanStart, cursor,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    if (isItalicActive) {
                        s.setSpan(new StyleSpan(Typeface.ITALIC), spanStart, cursor,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    if (isUnderlineActive) {
                        s.setSpan(new UnderlineSpan(), spanStart, cursor,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    charCountAdded = 0;
                }
                if (callback != null) callback.onContentChanged();
            }
        });
    }

    @Override
    public int getSegmentType() { return NoteSegment.TYPE_TEXT; }

    @Override
    public Object getSegmentData() { return editText.getText(); }
}