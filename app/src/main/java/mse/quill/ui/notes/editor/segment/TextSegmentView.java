package mse.quill.ui.notes.editor.segment;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.BulletSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import mse.quill.ui.notes.editor.model.NoteSegment;

public class TextSegmentView extends BaseSegmentView {

    /** Heading level is marked with an invisible zero-width-space text prefix rather than a
     *  size-only span: Html.toHtml (used by SpanSerializer) has no idea how to serialize a
     *  RelativeSizeSpan, so anything represented purely as a span would silently vanish on
     *  save. The marker survives any text round-trip for free (and is invisible, unlike a
     *  literal "# "); the RelativeSizeSpan/bold styling is always re-derived from it (see
     *  {@link #restyleHeadingLine}), both after edits and after loading. H1's marker is a prefix
     *  of H2's, so detection always checks the longer marker first. */
    private static final String HEADING_1_PREFIX = "​";
    private static final String HEADING_2_PREFIX = "​​";
    private static final float HEADING_1_SCALE = 1.6f;
    private static final float HEADING_2_SCALE = 1.3f;
    private static final int BULLET_GAP_WIDTH = 24;

    private final EditText editText;
    private boolean isBoldActive = false;
    private boolean isItalicActive = false;
    private boolean isUnderlineActive = false;
    private boolean isBulletListActive = false;

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
        restyleAllHeadings();
    }

    public Spannable getText() {
        return (Spannable) editText.getText();
    }

    public void focusAtEnd() {
        editText.requestFocus();
        editText.setSelection(editText.getText().length());
        showKeyboard();
    }

    // In TextSegmentView.java
    public EditText getEditText() { return editText; }

    public void focusAt(int position) {
        editText.requestFocus();
        editText.setSelection(Math.min(position, editText.getText().length()));
        showKeyboard();
    }

    public void focusAtStart() {
        editText.requestFocus();
        editText.setSelection(0);
        showKeyboard();
    }

    /** requestFocus() alone doesn't summon the IME — that normally only happens because the
     *  touch event itself landed on the EditText. Focusing programmatically (e.g. tapping empty
     *  space elsewhere in the note to keep writing) needs an explicit showSoftInput call. */
    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
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

    /** Toggles heading level on the line the cursor is in. A line can only be one heading level
     *  at a time, so applying one level replaces the other. */
    public void applyHeading(int level) {
        Editable text = editText.getText();
        int cursor = editText.getSelectionStart();
        int lineStart = findLineStart(text, cursor);
        int lineEnd = findLineEnd(text, cursor);
        String line = text.subSequence(lineStart, lineEnd).toString();

        int currentLevel = detectHeadingLevel(line);
        int currentMarkerLen = markerLength(currentLevel);
        String newPrefix = level == 1 ? HEADING_1_PREFIX : HEADING_2_PREFIX;

        int newCursor;
        if (currentLevel == level) {
            // Toggle off
            text.delete(lineStart, lineStart + currentMarkerLen);
            newCursor = Math.max(lineStart, cursor - currentMarkerLen);
        } else {
            if (currentMarkerLen > 0) {
                text.delete(lineStart, lineStart + currentMarkerLen);
            }
            text.insert(lineStart, newPrefix);
            newCursor = cursor - currentMarkerLen + newPrefix.length();
        }

        // Heading and list-continuation are mutually exclusive going forward.
        isBulletListActive = false;

        editText.setSelection(Math.max(0, Math.min(newCursor, text.length())));
        restyleCurrentLineHeading();
    }

    /** Toggles a bullet on the line the cursor is in. Unlike headings, BulletSpan is one of the
     *  span types Html.toHtml/fromHtml (SpanSerializer) already round-trips natively, so no text
     *  marker is needed here — same trust level this file already extends to bold/italic/underline.
     *  While active, {@link #continueBulletListIfActive()} keeps propagating the bullet onto
     *  whatever line the cursor is on (including new lines created by pressing Enter) until the
     *  user toggles it off again or applies a heading. */
    public void applyBulletList() {
        Editable text = editText.getText();
        int cursor = editText.getSelectionStart();
        int lineStart = findLineStart(text, cursor);
        int lineEnd = findLineEnd(text, cursor);

        BulletSpan[] existing = text.getSpans(lineStart, lineEnd, BulletSpan.class);
        if (existing.length > 0) {
            for (BulletSpan span : existing) text.removeSpan(span);
            isBulletListActive = false;
        } else {
            text.setSpan(new BulletSpan(BULLET_GAP_WIDTH), lineStart, lineEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            isBulletListActive = true;
        }
    }

    /** Called after every text change while list-continuation is active: reapplies a BulletSpan
     *  over whatever the current line is now, which naturally covers a freshly created line after
     *  the user presses Enter without needing to special-case newline detection. */
    private void continueBulletListIfActive() {
        if (!isBulletListActive) return;
        Editable text = editText.getText();
        int cursor = editText.getSelectionStart();
        int lineStart = findLineStart(text, cursor);
        int lineEnd = findLineEnd(text, cursor);
        for (BulletSpan span : text.getSpans(lineStart, lineEnd, BulletSpan.class)) {
            text.removeSpan(span);
        }
        text.setSpan(new BulletSpan(BULLET_GAP_WIDTH), lineStart, lineEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Stops list-continuation without touching any existing bullet — used when a segment is
     *  split by an image insert, since the split-off segment is no longer "the line being typed". */
    public void clearBulletContinuation() {
        isBulletListActive = false;
    }

    /** Full-document heading restyle — only used when loading persisted text, where every line
     *  needs evaluating at once. Never called during typing: touching spans on lines far from the
     *  cursor on every keystroke was churning the document broadly enough to interfere with
     *  in-progress text selection elsewhere in the segment. */
    private void restyleAllHeadings() {
        Editable text = editText.getText();
        int length = text.length();
        int lineStart = 0;
        while (lineStart <= length) {
            int lineEnd = findLineEnd(text, lineStart);
            restyleHeadingLine(text, lineStart, lineEnd);
            if (lineEnd >= length) break;
            lineStart = lineEnd + 1; // skip the '\n'
        }
    }

    /** Restyles only the line the cursor is currently in — used during typing and heading
     *  toggling, so edits never touch spans on unrelated lines. */
    private void restyleCurrentLineHeading() {
        Editable text = editText.getText();
        int cursor = editText.getSelectionStart();
        int lineStart = findLineStart(text, cursor);
        int lineEnd = findLineEnd(text, cursor);
        restyleHeadingLine(text, lineStart, lineEnd);
    }

    /** Recomputes heading RelativeSizeSpan/bold styling for one line from its marker prefix —
     *  the prefix (not the spans) is the source of truth. The companion bold span is a plain
     *  StyleSpan (not a custom subclass — subclassing a platform Parcelable span type risked
     *  interacting badly with Android's own span handling during selection/IME operations); it's
     *  identified for removal by exactly matching this line's bounds rather than by type, so it
     *  won't be confused with a StyleSpan(BOLD) the user applied manually over a different range. */
    private void restyleHeadingLine(Editable text, int lineStart, int lineEnd) {
        for (RelativeSizeSpan span : text.getSpans(lineStart, lineEnd, RelativeSizeSpan.class)) {
            text.removeSpan(span);
        }
        for (StyleSpan span : text.getSpans(lineStart, lineEnd, StyleSpan.class)) {
            if (span.getStyle() == Typeface.BOLD
                    && text.getSpanStart(span) == lineStart
                    && text.getSpanEnd(span) == lineEnd) {
                text.removeSpan(span);
            }
        }

        String line = text.subSequence(lineStart, lineEnd).toString();
        int level = detectHeadingLevel(line);
        if (level == 1) {
            text.setSpan(new RelativeSizeSpan(HEADING_1_SCALE), lineStart, lineEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new StyleSpan(Typeface.BOLD), lineStart, lineEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (level == 2) {
            text.setSpan(new RelativeSizeSpan(HEADING_2_SCALE), lineStart, lineEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new StyleSpan(Typeface.BOLD), lineStart, lineEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** 0 = not a heading, else the heading level — checks the longer (H2) marker first since it
     *  starts with H1's marker. */
    private static int detectHeadingLevel(String line) {
        if (line.startsWith(HEADING_2_PREFIX)) return 2;
        if (line.startsWith(HEADING_1_PREFIX)) return 1;
        return 0;
    }

    private static int markerLength(int level) {
        if (level == 1) return HEADING_1_PREFIX.length();
        if (level == 2) return HEADING_2_PREFIX.length();
        return 0;
    }

    private static int findLineStart(CharSequence text, int cursor) {
        int i = cursor;
        while (i > 0 && text.charAt(i - 1) != '\n') i--;
        return i;
    }

    private static int findLineEnd(CharSequence text, int cursor) {
        int i = cursor;
        while (i < text.length() && text.charAt(i) != '\n') i++;
        return i;
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
                restyleCurrentLineHeading();
                continueBulletListIfActive();
                if (callback != null) callback.onContentChanged();
            }
        });
    }

    @Override
    public int getSegmentType() { return NoteSegment.TYPE_TEXT; }

    @Override
    public Object getSegmentData() { return editText.getText(); }
}
