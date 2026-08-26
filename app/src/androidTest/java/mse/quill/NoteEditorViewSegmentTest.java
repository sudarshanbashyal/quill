package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.widget.EditText;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import mse.quill.ui.notes.editor.NoteEditorView;
import mse.quill.data.model.AudioSegment;
import mse.quill.data.model.ImageSegment;
import mse.quill.data.model.NoteSegment;
import mse.quill.data.model.TextSegment;
import mse.quill.ui.notes.editor.segment.BaseSegmentView;
import mse.quill.ui.notes.editor.segment.TextSegmentView;

/**
 * The editor's structural edits: splitting a paragraph to make room for a block, merging one back
 * into the paragraph above it, and deleting a block.
 *
 * <p>These are the operations that decide what a note *is* — every one of them rewrites the segment
 * list that {@code exportSegments} hands to the repository — and they were the last uncovered piece
 * of Epic A's test list. What makes them worth a test rather than a read-through is that each has a
 * boundary case that reads as a bug when it fires and as correct behaviour when you know the
 * reasoning: backspacing into a picture must *not* delete it, and the final segment of a note must
 * not be removable at all.
 */
@RunWith(AndroidJUnit4.class)
public class NoteEditorViewSegmentTest {

    private NoteEditorView editor;

    // ── Splitting for a block insert ─────────────────────────────────────────────────────

    @Test
    public void insertingAPictureMidParagraphSplitsItInTwo() {
        onMainThread(() -> {
            loadText("Before the picture. After the picture.");
            caretAt(0, "Before the picture.".length());

            editor.insertImageAfterFocused("/data/pic.jpg");

            assertTypes(NoteSegment.TYPE_TEXT, NoteSegment.TYPE_IMAGE, NoteSegment.TYPE_TEXT);
            assertEquals("Before the picture.", textOf(0));
            assertEquals(" After the picture.", textOf(2));
        });
    }

    @Test
    public void aPictureAtTheEndOfAParagraphStillLeavesALineToCarryOnTyping() {
        onMainThread(() -> {
            loadText("All of the text.");
            caretAt(0, "All of the text.".length());

            editor.insertImageAfterFocused("/data/pic.jpg");

            assertTypes(NoteSegment.TYPE_TEXT, NoteSegment.TYPE_IMAGE, NoteSegment.TYPE_TEXT);
            assertEquals("All of the text.", textOf(0));
            assertEquals("a note that ends in a block has nowhere for the caret to go", "", textOf(2));
        });
    }

    @Test
    public void aPictureAtTheStartOfAParagraphLeavesAnEmptyLineAboveIt() {
        onMainThread(() -> {
            loadText("All of the text.");
            caretAt(0, 0);

            editor.insertImageAfterFocused("/data/pic.jpg");

            assertTypes(NoteSegment.TYPE_TEXT, NoteSegment.TYPE_IMAGE, NoteSegment.TYPE_TEXT);
            assertEquals("", textOf(0));
            assertEquals("All of the text.", textOf(2));
        });
    }

    @Test
    public void insertingARecordingSplitsTheSameWayAPictureDoes() {
        onMainThread(() -> {
            loadText("Lecture note. Rest of it.");
            caretAt(0, "Lecture note.".length());

            editor.insertAudioAfterFocused("/data/clip.m4a", 4200);

            assertTypes(NoteSegment.TYPE_TEXT, NoteSegment.TYPE_AUDIO, NoteSegment.TYPE_TEXT);
            assertEquals("Lecture note.", textOf(0));
            assertEquals(" Rest of it.", textOf(2));

            List<NoteSegment> exported = editor.exportSegments();
            AudioSegment audio = (AudioSegment) exported.get(1);
            assertEquals("/data/clip.m4a", audio.filePath);
            assertEquals("the recording's length did not survive the insert", 4200, audio.durationMs);
        });
    }

    @Test
    public void splittingCarriesTheFormattingOnEachSideWithIt() {
        onMainThread(() -> {
            // "Bold text" is bold; " plain text" is not. The split lands between them.
            SpannableStringBuilder content = new SpannableStringBuilder("Bold text plain text");
            content.setSpan(new StyleSpan(Typeface.BOLD), 0, "Bold text".length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            loadSegments(new TextSegment(content));
            caretAt(0, "Bold text".length());

            editor.insertImageAfterFocused("/data/pic.jpg");

            assertTrue("the bold half lost its formatting", isBoldAt(0, 0));
            assertTrue("formatting leaked across the split into the second half",
                    !isBoldAt(2, 0));
        });
    }

    /**
     * With no caret anywhere — a note loaded but not yet touched — {@code getFocusedSegmentIndex}
     * falls back to the last segment rather than refusing, and that segment's caret is at 0. So the
     * block lands <em>above</em> the final paragraph, not after it. Pinned down here because it is
     * surprising read cold, and because nothing else in the suite says what the fallback does.
     */
    @Test
    public void aBlockInsertedWithNothingFocusedLandsAboveTheLastParagraph() {
        onMainThread(() -> {
            loadText("Some text.");

            editor.insertImageAfterFocused("/data/pic.jpg");

            assertTypes(NoteSegment.TYPE_TEXT, NoteSegment.TYPE_IMAGE, NoteSegment.TYPE_TEXT);
            assertEquals("", textOf(0));
            assertEquals("no text should have been lost", "Some text.", textOf(2));
        });
    }

    // ── Backspace at a segment boundary ──────────────────────────────────────────────────

    @Test
    public void backspacingAtTheStartOfAParagraphMergesItIntoTheOneAbove() {
        onMainThread(() -> {
            loadSegments(text("First half."), text("Second half."));

            merge(1);

            assertTypes(NoteSegment.TYPE_TEXT);
            assertEquals("First half.Second half.", textOf(0));
        });
    }

    @Test
    public void theCaretLandsWhereTheTwoParagraphsMeet() {
        onMainThread(() -> {
            loadSegments(text("First half."), text("Second half."));

            merge(1);

            assertEquals("the caret should sit at the join, not at either end",
                    "First half.".length(), editTextAt(0).getSelectionStart());
        });
    }

    @Test
    public void mergingKeepsTheFormattingOfBothHalves() {
        onMainThread(() -> {
            SpannableStringBuilder first = new SpannableStringBuilder("Bold.");
            first.setSpan(new StyleSpan(Typeface.BOLD), 0, first.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            loadSegments(new TextSegment(first), text("Plain."));

            merge(1);

            assertTypes(NoteSegment.TYPE_TEXT);
            assertTrue("the first half lost its bold in the merge", isBoldAt(0, 0));
            assertTrue("bold spread into the second half during the merge",
                    !isBoldAt(0, "Bold.".length()));
        });
    }

    @Test
    public void backspacingInTheFirstParagraphOfANoteDoesNothing() {
        onMainThread(() -> {
            loadSegments(text("Only paragraph."), text("Another."));

            merge(0);

            assertTypes(NoteSegment.TYPE_TEXT, NoteSegment.TYPE_TEXT);
            assertEquals("Only paragraph.", textOf(0));
            assertEquals("Another.", textOf(1));
        });
    }

    /**
     * Deliberate, and the comment in {@code onRequestMergeWithPrevious} says why: this used to
     * delete the picture, so one keypress on the line below could destroy it with no confirmation
     * and no undo.
     */
    @Test
    public void backspacingIntoAPictureLeavesItAlone() {
        onMainThread(() -> {
            loadSegments(text("Above."), new ImageSegment("/data/pic.jpg"), text("Below."));

            merge(2);

            assertTypes(NoteSegment.TYPE_TEXT, NoteSegment.TYPE_IMAGE, NoteSegment.TYPE_TEXT);
            assertEquals("the text below the picture was swallowed", "Below.", textOf(2));
        });
    }

    // ── Deleting a segment ───────────────────────────────────────────────────────────────

    @Test
    public void deletingABlockRemovesItAndLeavesTheTextEitherSide() {
        onMainThread(() -> {
            loadSegments(text("Above."), new ImageSegment("/data/pic.jpg"), text("Below."));

            delete(1);

            assertTypes(NoteSegment.TYPE_TEXT, NoteSegment.TYPE_TEXT);
            assertEquals("Above.", textOf(0));
            assertEquals("Below.", textOf(1));
        });
    }

    @Test
    public void theLastSegmentOfANoteCannotBeDeleted() {
        onMainThread(() -> {
            loadSegments(text("The only thing here."));

            delete(0);

            assertTypes(NoteSegment.TYPE_TEXT);
            assertEquals("an emptied editor has no field to type in",
                    "The only thing here.", textOf(0));
        });
    }

    @Test
    public void deletingASegmentIsVisibleToTheRepositoryAsWellAsTheScreen() {
        onMainThread(() -> {
            loadSegments(text("Above."), new ImageSegment("/data/pic.jpg"), text("Below."));

            delete(1);

            List<Integer> exportedTypes = new ArrayList<>();
            for (NoteSegment segment : editor.exportSegments()) exportedTypes.add(segment.type());
            assertEquals("the deleted block would still be saved",
                    Arrays.asList(NoteSegment.TYPE_TEXT, NoteSegment.TYPE_TEXT), exportedTypes);
        });
    }

    // ── Harness ──────────────────────────────────────────────────────────────────────────

    /** Views and their TextWatchers must be touched on the main thread. */
    private void onMainThread(Runnable action) {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                // The app's own theme, not the bare application context: segment views inflate
                // Material widgets, and MaterialButton refuses to construct under a theme that
                // isn't a Theme.MaterialComponents descendant.
                Context context = new ContextThemeWrapper(
                        ApplicationProvider.getApplicationContext(), R.style.Theme_Quill);
                editor = new NoteEditorView(context);
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        if (thrown.get() instanceof AssertionError) throw (AssertionError) thrown.get();
        if (thrown.get() != null) throw new AssertionError(thrown.get());
    }

    private void loadText(String content) {
        loadSegments(text(content));
    }

    private void loadSegments(NoteSegment... segments) {
        editor.loadSegments(Arrays.asList(segments));
    }

    private static TextSegment text(String content) {
        return new TextSegment(new SpannableStringBuilder(content));
    }

    /** Puts the caret in a text segment, the way tapping into it would. */
    private void caretAt(int segmentIndex, int position) {
        EditText field = editTextAt(segmentIndex);
        field.requestFocus();
        field.setSelection(position);
        assertSame("the test could not place the caret, so the split under test never happened",
                field, editor.getFocusedField());
    }

    /** What the segment reports when the user backspaces at position 0. */
    private void merge(int segmentIndex) {
        editor.onRequestMergeWithPrevious(editor.getSegments().get(segmentIndex));
    }

    /** What a long-press → delete on a block reports. */
    private void delete(int segmentIndex) {
        editor.onRequestDelete(editor.getSegments().get(segmentIndex));
    }

    private EditText editTextAt(int index) {
        BaseSegmentView view = editor.getSegments().get(index);
        assertTrue("segment " + index + " is not a text segment", view instanceof TextSegmentView);
        return ((TextSegmentView) view).getEditText();
    }

    private String textOf(int index) {
        return editTextAt(index).getText().toString();
    }

    private boolean isBoldAt(int segmentIndex, int charIndex) {
        android.text.Editable content = editTextAt(segmentIndex).getText();
        for (StyleSpan span : content.getSpans(charIndex, charIndex + 1, StyleSpan.class)) {
            if (span.getStyle() == Typeface.BOLD
                    && content.getSpanStart(span) <= charIndex
                    && content.getSpanEnd(span) > charIndex) {
                return true;
            }
        }
        return false;
    }

    private void assertTypes(int... expected) {
        List<Integer> actual = new ArrayList<>();
        for (BaseSegmentView view : editor.getSegments()) actual.add(view.getSegmentType());
        List<Integer> wanted = new ArrayList<>();
        for (int type : expected) wanted.add(type);
        assertEquals("the editor's segment list is not what the edit should have left",
                wanted, actual);
    }
}
