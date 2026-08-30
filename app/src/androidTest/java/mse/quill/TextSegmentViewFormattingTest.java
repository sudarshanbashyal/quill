package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.widget.EditText;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

import mse.quill.data.model.HeadingMarker;
import mse.quill.ui.notes.editor.segment.TextSegmentView;

/** Covers formatting that's toggled on with no selection and then carried into typed text. */
@RunWith(AndroidJUnit4.class)
public class TextSegmentViewFormattingTest {

    private TextSegmentView view;
    private EditText editText;

    /** Views and their TextWatchers must be touched on the main thread. */
    private void onMainThread(Runnable action) {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Context context = ApplicationProvider.getApplicationContext();
                view = new TextSegmentView(context);
                editText = view.getEditText();
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        if (thrown.get() instanceof AssertionError) throw (AssertionError) thrown.get();
        if (thrown.get() != null) throw new AssertionError(thrown.get());
    }

    /** Emulates the IME committing text at the cursor. */
    private void type(String input) {
        editText.getText().insert(editText.getSelectionStart(), input);
    }

    private boolean hasStyleAt(int index, int style) {
        Editable text = editText.getText();
        for (StyleSpan span : text.getSpans(index, index + 1, StyleSpan.class)) {
            if (span.getStyle() == style
                    && text.getSpanStart(span) <= index
                    && text.getSpanEnd(span) > index) {
                return true;
            }
        }
        return false;
    }

    private boolean isBoldAt(int index) { return hasStyleAt(index, Typeface.BOLD); }

    private boolean isItalicAt(int index) { return hasStyleAt(index, Typeface.ITALIC); }

    private boolean isUnderlinedAt(int index) {
        Editable text = editText.getText();
        for (UnderlineSpan span : text.getSpans(index, index + 1, UnderlineSpan.class)) {
            if (text.getSpanStart(span) <= index && text.getSpanEnd(span) > index) return true;
        }
        return false;
    }

    // ── Same line: the case that already worked ────────────────────────────

    @Test
    public void boldAppliesToTextTypedOnTheSameLine() {
        onMainThread(() -> {
            type("hi ");
            view.applyBold();
            type("ab");

            assertTrue("first bold char", isBoldAt(3));
            assertTrue("second bold char", isBoldAt(4));
        });
    }

    // ── Across a newline: the reported bug ─────────────────────────────────

    @Test
    public void boldSurvivesPressingEnter() {
        onMainThread(() -> {
            type("hi");
            view.applyBold();
            type("\n");
            type("ab");

            assertEquals("hi\nab", editText.getText().toString());
            assertTrue("first char on the new line should be bold", isBoldAt(3));
            assertTrue("second char on the new line should be bold", isBoldAt(4));
        });
    }

    @Test
    public void italicSurvivesPressingEnter() {
        onMainThread(() -> {
            type("hi");
            view.applyItalic();
            type("\n");
            type("ab");

            assertTrue("first char on the new line should be italic", isItalicAt(3));
            assertTrue("second char on the new line should be italic", isItalicAt(4));
        });
    }

    @Test
    public void underlineSurvivesPressingEnter() {
        onMainThread(() -> {
            type("hi");
            view.applyUnderline();
            type("\n");
            type("ab");

            assertTrue("first char on the new line should be underlined", isUnderlinedAt(3));
            assertTrue("second char on the new line should be underlined", isUnderlinedAt(4));
        });
    }

    /** Typing the very first character of an empty segment is the same single-char-line shape. */
    @Test
    public void boldAppliesToTheFirstCharacterOfAnEmptySegment() {
        onMainThread(() -> {
            view.applyBold();
            type("a");

            assertTrue("the only char should be bold", isBoldAt(0));
        });
    }

    // ── Heading styling must still be derived, not user-owned ──────────────

    /** The derived heading bold must still be cleared when the heading marker goes away. */
    @Test
    public void headingBoldIsRemovedWhenTheHeadingIsToggledOff() {
        onMainThread(() -> {
            type("Title");
            view.applyHeading(1);
            assertTrue("heading text should render bold",
                    isBoldAt(HeadingMarker.length(HeadingMarker.H1)));

            view.applyHeading(1); // toggle off
            assertEquals("Title", editText.getText().toString());
            assertFalse("heading bold should be gone", isBoldAt(0));
        });
    }

    /** A heading line the user also typed bold text into keeps that text bold after a re-style. */
    @Test
    public void headingDoesNotSwallowUserBoldOnTheSameLine() {
        onMainThread(() -> {
            view.applyHeading(1);
            view.applyBold();
            type("Hi");

            view.applyHeading(1); // toggle the heading back off
            assertTrue("user's own bold should survive", isBoldAt(0));
        });
    }
}
