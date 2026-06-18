package mse.quill.ui.notes.editor;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import mse.quill.ui.notes.editor.segment.BaseSegmentView;
import mse.quill.ui.notes.editor.segment.ImageSegmentView;
import mse.quill.ui.notes.editor.segment.TextSegmentView;

public class NoteEditorView extends LinearLayout implements BaseSegmentView.SegmentCallback {

    private final List<BaseSegmentView> segments = new ArrayList<>();

    public interface ContentChangeListener {
        void onContentChanged();
    }

    private ContentChangeListener contentChangeListener;

    public NoteEditorView(Context context) {
        super(context);
        init();
    }

    public NoteEditorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        // Start with one empty text segment
        addTextSegment(new SpannableStringBuilder(""), -1);
    }

    public void setContentChangeListener(ContentChangeListener listener) {
        this.contentChangeListener = listener;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    public void insertImageAfterFocused(String filePath) {
        int focusedIndex = getFocusedSegmentIndex();

        // If focused segment is a text segment, split it at cursor
        if (focusedIndex >= 0 && segments.get(focusedIndex) instanceof TextSegmentView) {
            TextSegmentView textView = (TextSegmentView) segments.get(focusedIndex);
            android.widget.EditText editText = textView.getEditText();
            int cursor = editText.getSelectionStart();

            SpannableStringBuilder before = new SpannableStringBuilder(
                    editText.getText().subSequence(0, cursor));
            SpannableStringBuilder after = new SpannableStringBuilder(
                    editText.getText().subSequence(cursor, editText.getText().length()));

            // Update current segment with text before cursor
            textView.setText(before);

            // Insert image after current segment
            int insertAt = focusedIndex + 1;
            addImageSegment(filePath, insertAt);

            // Insert text segment after image with remaining text
            addTextSegment(after, insertAt + 1);

            // Focus the new text segment after image
            ((TextSegmentView) segments.get(insertAt + 1)).focusAtStart();

        } else {
            // No focused text — append image + new text at end
            addImageSegment(filePath, segments.size());
            addTextSegment(new SpannableStringBuilder(""), segments.size());
            ((TextSegmentView) segments.get(segments.size() - 1)).focusAtStart();
        }
    }

    public void applyBoldToFocused() {
        TextSegmentView focused = getFocusedTextSegment();
        if (focused != null) focused.applyBold();
    }

    public void applyItalicToFocused() {
        TextSegmentView focused = getFocusedTextSegment();
        if (focused != null) focused.applyItalic();
    }

    public void applyUnderlineToFocused() {
        TextSegmentView focused = getFocusedTextSegment();
        if (focused != null) focused.applyUnderline();
    }

    public boolean isBoldActive() {
        TextSegmentView focused = getFocusedTextSegment();
        return focused != null && focused.isBoldActive();
    }

    public boolean isItalicActive() {
        TextSegmentView focused = getFocusedTextSegment();
        return focused != null && focused.isItalicActive();
    }

    public boolean isUnderlineActive() {
        TextSegmentView focused = getFocusedTextSegment();
        return focused != null && focused.isUnderlineActive();
    }

    public List<BaseSegmentView> getSegments() {
        return segments;
    }

    // ── SegmentCallback ────────────────────────────────────────────────────

    @Override
    public void onRequestSplitAt(BaseSegmentView segment, int cursorPosition) {
        // Tap on image → insert a new text segment after it
        int index = segments.indexOf(segment);
        if (index >= 0) {
            addTextSegment(new SpannableStringBuilder(""), index + 1);
            ((TextSegmentView) segments.get(index + 1)).focusAtStart();
        }
    }

    @Override
    public void onRequestDelete(BaseSegmentView segment) {
        int index = segments.indexOf(segment);
        if (index < 0) return;

        // Never delete the last segment if it's the only one
        if (segments.size() == 1) return;

        removeSegment(index);

        // Focus adjacent segment
        int focusIndex = Math.max(0, index - 1);
        if (segments.get(focusIndex) instanceof TextSegmentView) {
            ((TextSegmentView) segments.get(focusIndex)).focusAtEnd();
        }
    }

    @Override
    public void onRequestMergeWithPrevious(BaseSegmentView segment) {
        int index = segments.indexOf(segment);
        if (index <= 0) return; // nothing before it

        BaseSegmentView previous = segments.get(index - 1);

        if (previous instanceof TextSegmentView && segment instanceof TextSegmentView) {
            // Merge two text segments
            TextSegmentView prevText = (TextSegmentView) previous;
            TextSegmentView currText = (TextSegmentView) segment;

            int mergePoint = prevText.getText().length();

            SpannableStringBuilder merged = new SpannableStringBuilder();
            merged.append(prevText.getText());
            merged.append(currText.getText());

            prevText.setText(merged);
            prevText.focusAt(mergePoint);

            removeSegment(index);

        } else if (previous instanceof ImageSegmentView) {
            // Backspace at start of text after image — delete the image
            onRequestDelete(previous);
        }
    }

    @Override
    public void onContentChanged() {
        if (contentChangeListener != null) contentChangeListener.onContentChanged();
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private void addTextSegment(SpannableStringBuilder content, int index) {
        TextSegmentView view = new TextSegmentView(getContext());
        view.setText(content);
        view.setCallback(this);
        insertSegment(view, index);
    }

    private void addImageSegment(String filePath, int index) {
        ImageSegmentView view = new ImageSegmentView(getContext(), filePath);
        view.setCallback(this);
        insertSegment(view, index);
    }

    private void insertSegment(BaseSegmentView view, int index) {
        if (index < 0 || index >= segments.size()) {
            segments.add(view);
            addView(view);
        } else {
            segments.add(index, view);
            addView(view, index);
        }
    }

    private void removeSegment(int index) {
        BaseSegmentView view = segments.remove(index);
        removeView(view);
    }

    private int getFocusedSegmentIndex() {
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).hasFocus()) return i;
        }
        return segments.size() - 1; // default to last
    }

    private TextSegmentView getFocusedTextSegment() {
        for (BaseSegmentView seg : segments) {
            if (seg.hasFocus() && seg instanceof TextSegmentView) {
                return (TextSegmentView) seg;
            }
        }
        return null;
    }
}