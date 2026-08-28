package mse.quill.ui.notes.editor;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.BulletSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import mse.quill.data.model.HeadingMarker;

/**
 * One independently editable, rich-text region of a note.
 *
 * <p>Extracted from {@code TextSegmentView} so a segment isn't forced to be exactly one editable:
 * a body segment wraps one of these, a Q&amp;A segment wraps two. The formatting rules are subtle
 * enough (derived heading spans, bullet continuation, active-format typing) that having a second
 * copy of them would guarantee the two drifted apart.
 *
 * <p>What a field permits is a property of the field, not of the toolbar — {@link
 * #setHeadingsAllowed} is how a Q&amp;A field refuses headings, and the toolbar greys the control
 * out by asking the focused field rather than by knowing what kind of segment it is in.
 */
public class RichTextField extends EditText {

    public interface Listener {
        void onContentChanged();
        void onSelectionChanged();
        /** Backspace pressed with the caret at position 0. @return true if the host consumed it. */
        boolean onBackspaceAtStart();
    }

    private static final float HEADING_1_SCALE = 1.6f;
    private static final float HEADING_2_SCALE = 1.3f;
    private static final int BULLET_GAP_WIDTH = 24;

    /** The heading size/bold spans this field applied, held by identity so a restyle removes only
     *  its own derived styling and never the user's. Cleared with the content in {@link #setRichText}. */
    private final Set<Object> derivedHeadingSpans =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private Listener listener;
    private boolean headingsAllowed = true;

    private boolean isBoldActive = false;
    private boolean isItalicActive = false;
    private boolean isUnderlineActive = false;
    private boolean isBulletListActive = false;

    public RichTextField(Context context) {
        super(context);
        setBackground(null);
        setTextSize(16);
        setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setupKeyListener();
        setupTextWatcher();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Q&A fields set this false: a question or answer is a leaf of prose, not a place for
     *  document structure. The toolbar reads it back via {@link #areHeadingsAllowed()}. */
    public void setHeadingsAllowed(boolean allowed) {
        this.headingsAllowed = allowed;
    }

    public boolean areHeadingsAllowed() {
        return headingsAllowed;
    }

    // ── Content ────────────────────────────────────────────────────────────

    public void setRichText(Spannable text) {
        // The old spans go with the old content; keeping them would leak an entry per load.
        derivedHeadingSpans.clear();
        setText(text);
        restyleAllHeadings();
    }

    public Spannable getRichText() {
        return (Spannable) getText();
    }

    public void focusAtStart() {
        requestFocus();
        setSelection(0);
        showKeyboard();
    }

    public void focusAtEnd() {
        requestFocus();
        setSelection(getText().length());
        showKeyboard();
    }

    public void focusAt(int position) {
        requestFocus();
        setSelection(Math.min(position, getText().length()));
        showKeyboard();
    }

    /** requestFocus() alone doesn't summon the IME — that normally only happens because the touch
     *  event itself landed on the field. Focusing programmatically (e.g. tapping empty space
     *  elsewhere in the note to keep writing) needs an explicit showSoftInput call. */
    private void showKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    }

    // ── Inline formatting ──────────────────────────────────────────────────

    public void applyBold() { applyStyle(Typeface.BOLD); }
    public void applyItalic() { applyStyle(Typeface.ITALIC); }
    public void applyUnderline() { applyStyle(-1); } // -1 signals underline

    public boolean isBoldActive() { return isBoldActive; }
    public boolean isItalicActive() { return isItalicActive; }
    public boolean isUnderlineActive() { return isUnderlineActive; }

    private void applyStyle(int style) {
        Editable text = getText();
        int start = getSelectionStart();
        int end = getSelectionEnd();

        if (start == end) {
            // No selection — toggle active mode for future typing
            if (style == Typeface.BOLD) {
                isBoldActive = !isBoldActive;
            } else if (style == Typeface.ITALIC) {
                isItalicActive = !isItalicActive;
            } else if (style == -1) {
                isUnderlineActive = !isUnderlineActive;
            }
        } else {
            if (style == -1) {
                toggleUnderlineOnSelection(text, start, end);
            } else {
                toggleStyleOnSelection(text, start, end, style);
            }
        }
    }

    private void toggleStyleOnSelection(Editable text, int start, int end, int typeface) {
        StyleSpan[] spans = text.getSpans(start, end, StyleSpan.class);
        boolean alreadyHasStyle = false;
        for (StyleSpan span : spans) {
            if (span.getStyle() == typeface) {
                alreadyHasStyle = true;
                text.removeSpan(span);
            }
        }
        if (!alreadyHasStyle) {
            text.setSpan(new StyleSpan(typeface), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void toggleUnderlineOnSelection(Editable text, int start, int end) {
        UnderlineSpan[] spans = text.getSpans(start, end, UnderlineSpan.class);
        if (spans.length > 0) {
            for (UnderlineSpan span : spans) text.removeSpan(span);
        } else {
            text.setSpan(new UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ── Headings ───────────────────────────────────────────────────────────

    /** Toggles heading level on the line the caret is in. A line can only be one heading level at
     *  a time, so applying one level replaces the other. No-op where headings aren't allowed. */
    public void applyHeading(int level) {
        if (!headingsAllowed) return;

        Editable text = getText();
        int cursor = getSelectionStart();
        int lineStart = findLineStart(text, cursor);
        String line = text.subSequence(lineStart, findLineEnd(text, cursor)).toString();

        int currentLevel = HeadingMarker.levelOf(line);
        int currentMarkerLen = HeadingMarker.length(currentLevel);
        String newPrefix = HeadingMarker.forLevel(level);

        int newCursor;
        if (currentLevel == level) {
            text.delete(lineStart, lineStart + currentMarkerLen);
            newCursor = Math.max(lineStart, cursor - currentMarkerLen);
        } else {
            if (currentMarkerLen > 0) text.delete(lineStart, lineStart + currentMarkerLen);
            text.insert(lineStart, newPrefix);
            newCursor = cursor - currentMarkerLen + newPrefix.length();
        }

        // Heading and list-continuation are mutually exclusive going forward.
        isBulletListActive = false;

        setSelection(Math.max(0, Math.min(newCursor, text.length())));
        restyleCurrentLineHeading();
    }

    /** Heading level of the line the caret is in, or {@link HeadingMarker#NONE} — drives the
     *  toolbar's H1/H2 markers, which unlike bold/italic/underline are a property of the current
     *  line rather than a pending typing mode. */
    public int currentHeadingLevel() {
        if (!headingsAllowed) return HeadingMarker.NONE;
        Editable text = getText();
        int cursor = getSelectionStart();
        if (cursor < 0) return HeadingMarker.NONE;
        return HeadingMarker.levelOf(text, findLineStart(text, cursor), findLineEnd(text, cursor));
    }

    /** Full-document heading restyle — only used when loading persisted text, where every line
     *  needs evaluating at once. Never called during typing: touching spans on lines far from the
     *  caret on every keystroke churned the document broadly enough to interfere with in-progress
     *  text selection elsewhere in the field. */
    private void restyleAllHeadings() {
        if (!headingsAllowed) return;
        Editable text = getText();
        int length = text.length();
        int lineStart = 0;
        while (lineStart <= length) {
            int lineEnd = findLineEnd(text, lineStart);
            restyleHeadingLine(text, lineStart, lineEnd);
            if (lineEnd >= length) break;
            lineStart = lineEnd + 1; // skip the '\n'
        }
    }

    private void restyleCurrentLineHeading() {
        if (!headingsAllowed) return;
        Editable text = getText();
        int cursor = getSelectionStart();
        restyleHeadingLine(text, findLineStart(text, cursor), findLineEnd(text, cursor));
    }

    /** Recomputes heading styling for one line from its marker prefix — the prefix (not the spans)
     *  is the source of truth. The companion bold span is a plain StyleSpan (subclassing a platform
     *  Parcelable span type risked interacting badly with Android's own span handling during
     *  selection/IME operations), so the spans this method created are tracked by identity and only
     *  those are removed. Recognising them by their bounds instead used to be enough, until it
     *  wasn't: the first character typed on any line produces a user bold span covering exactly
     *  [lineStart, lineEnd), indistinguishable from heading styling by bounds alone — so active-bold
     *  typing lost its first character on every new line. */
    private void restyleHeadingLine(Editable text, int lineStart, int lineEnd) {
        for (Object span : text.getSpans(lineStart, lineEnd, Object.class)) {
            if (derivedHeadingSpans.remove(span)) text.removeSpan(span);
        }

        String line = text.subSequence(lineStart, lineEnd).toString();
        int level = HeadingMarker.levelOf(line);
        if (level == HeadingMarker.NONE) return;

        float scale = level == HeadingMarker.H1 ? HEADING_1_SCALE : HEADING_2_SCALE;
        applyDerivedSpan(text, new RelativeSizeSpan(scale), lineStart, lineEnd);
        applyDerivedSpan(text, new StyleSpan(Typeface.BOLD), lineStart, lineEnd);
    }

    private void applyDerivedSpan(Editable text, Object span, int lineStart, int lineEnd) {
        derivedHeadingSpans.add(span);
        text.setSpan(span, lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ── Bullets ────────────────────────────────────────────────────────────

    /** Toggles a bullet on the line the caret is in. Unlike headings, a bullet needs no text
     *  marker: MarkdownSerializer reads the BulletSpan directly when writing the line's "- "
     *  prefix. While active, continuation keeps propagating the bullet onto whatever line the
     *  caret is on (including new lines from Enter) until toggled off or a heading is applied. */
    public void applyBulletList() {
        Editable text = getText();
        int cursor = getSelectionStart();
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

    private void continueBulletListIfActive() {
        if (!isBulletListActive) return;
        Editable text = getText();
        int cursor = getSelectionStart();
        int lineStart = findLineStart(text, cursor);
        int lineEnd = findLineEnd(text, cursor);
        for (BulletSpan span : text.getSpans(lineStart, lineEnd, BulletSpan.class)) {
            text.removeSpan(span);
        }
        text.setSpan(new BulletSpan(BULLET_GAP_WIDTH), lineStart, lineEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Stops list-continuation without touching any existing bullet — used when a field is split
     *  by an embed insert, since the split-off part is no longer "the line being typed". */
    public void clearBulletContinuation() {
        isBulletListActive = false;
    }

    /** Whether the line the caret is in is bulleted. Read from the span rather than from the
     *  continuation flag, which only tracks whether continuation is armed for typing. */
    public boolean isBulletActive() {
        Editable text = getText();
        int cursor = getSelectionStart();
        if (cursor < 0) return false;
        int lineStart = findLineStart(text, cursor);
        int lineEnd = findLineEnd(text, cursor);
        return text.getSpans(lineStart, lineEnd, BulletSpan.class).length > 0;
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private static int findLineStart(CharSequence text, int cursor) {
        int i = Math.max(0, cursor);
        while (i > 0 && text.charAt(i - 1) != '\n') i--;
        return i;
    }

    private static int findLineEnd(CharSequence text, int cursor) {
        int i = Math.max(0, cursor);
        while (i < text.length() && text.charAt(i) != '\n') i++;
        return i;
    }

    private void setupKeyListener() {
        setOnKeyListener((v, keyCode, event) -> keyCode == KeyEvent.KEYCODE_DEL
                && event.getAction() == KeyEvent.ACTION_DOWN
                && caretAtStart()
                && fireBackspaceAtStart());
    }

    /**
     * The same backspace, caught where soft keyboards actually deliver it.
     *
     * <p>{@link #setupKeyListener} only sees real key events, which is what a hardware keyboard
     * sends. Most IMEs don't: with the caret at 0 and nothing to delete they call
     * {@code deleteSurroundingText} — or route a synthetic DEL through {@code sendKeyEvent}, which
     * also bypasses the view's key listener. Backspacing out of a block therefore worked on a
     * laptop and did nothing at all on a phone, which is the only place it matters.
     */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection connection = super.onCreateInputConnection(outAttrs);
        if (connection == null) return null;
        return new InputConnectionWrapper(connection, true) {
            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (beforeLength == 1 && afterLength == 0
                        && caretAtStart() && fireBackspaceAtStart()) {
                    return true;
                }
                return super.deleteSurroundingText(beforeLength, afterLength);
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getKeyCode() == KeyEvent.KEYCODE_DEL
                        && caretAtStart() && fireBackspaceAtStart()) {
                    return true;
                }
                return super.sendKeyEvent(event);
            }
        };
    }

    /** Caret collapsed at the very start — the only position a backspace has nothing of its own
     *  to delete, and so the only one the host gets asked about. */
    private boolean caretAtStart() {
        return getSelectionStart() == 0 && getSelectionEnd() == 0;
    }

    private boolean fireBackspaceAtStart() {
        return listener != null && listener.onBackspaceAtStart();
    }

    private void setupTextWatcher() {
        addTextChangedListener(new TextWatcher() {
            private int charCountAdded = 0;

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                charCountAdded = count;
            }

            @Override
            public void afterTextChanged(Editable s) {
                int cursor = getSelectionStart();
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
                if (listener != null) listener.onContentChanged();
            }
        });
    }

    /** Reports caret moves. The toolbar's heading/bullet markers describe the line the caret is in,
     *  so they go stale on a plain EditText, which only reports text changes. */
    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        // Fires during the superclass constructor, before `listener` can possibly be set.
        if (listener != null) listener.onSelectionChanged();
    }

    /**
     * Also reports focus moves, because a selection change alone doesn't cover them: moving from
     * one field to another lands the caret at an offset it may already have been at, and Android
     * fires no selection callback when the value doesn't actually change. Focus is the thing that
     * decides *which field's* capabilities the toolbar is showing, so it has to be reported in its
     * own right — otherwise stepping out of a Q&A block into body text leaves headings and embeds
     * greyed out.
     */
    @Override
    protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        if (listener != null) listener.onSelectionChanged();
    }
}
