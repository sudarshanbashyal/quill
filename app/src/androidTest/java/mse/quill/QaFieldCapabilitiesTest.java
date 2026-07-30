package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.SpannableStringBuilder;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

import mse.quill.ui.notes.editor.RichTextField;
import mse.quill.ui.notes.editor.model.HeadingMarker;
import mse.quill.ui.notes.editor.segment.QASegmentView;
import mse.quill.ui.notes.editor.segment.TextSegmentView;

/**
 * What a Q&A field permits, which is what the toolbar greys itself out from. Kept at the field
 * level because that's where the rule lives — the toolbar never learns what a Q&A is.
 */
@RunWith(AndroidJUnit4.class)
public class QaFieldCapabilitiesTest {

    private void onMainThread(Runnable action) {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        if (thrown.get() instanceof AssertionError) throw (AssertionError) thrown.get();
        if (thrown.get() != null) throw new AssertionError(thrown.get());
    }

    private static Context context() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void bodyFieldAllowsHeadings() {
        onMainThread(() -> assertTrue(new TextSegmentView(context()).getField().areHeadingsAllowed()));
    }

    @Test
    public void bothQaFieldsRefuseHeadings() {
        onMainThread(() -> {
            QASegmentView qa = new QASegmentView(context(), null);
            assertFalse("question field", qa.getQuestionField().areHeadingsAllowed());
            assertFalse("answer field", qa.getAnswerField().areHeadingsAllowed());
        });
    }

    /** Refusing headings has to mean refusing them for real, not just hiding the button. */
    @Test
    public void applyingAHeadingInAQaFieldDoesNothing() {
        onMainThread(() -> {
            QASegmentView qa = new QASegmentView(context(), null);
            RichTextField question = qa.getQuestionField();
            question.setRichText(new SpannableStringBuilder("plain"));

            question.applyHeading(1);

            assertEquals("text must be untouched", "plain", question.getText().toString());
            assertEquals(HeadingMarker.NONE, question.currentHeadingLevel());
        });
    }

    /** Inline formatting and bullets stay available in both halves — that's the whole point. */
    @Test
    public void qaFieldsStillSupportInlineFormattingAndBullets() {
        onMainThread(() -> {
            QASegmentView qa = new QASegmentView(context(), null);
            for (RichTextField field : new RichTextField[]{qa.getQuestionField(), qa.getAnswerField()}) {
                field.setRichText(new SpannableStringBuilder("word"));

                field.applyBold();
                assertTrue("bold should arm for typing", field.isBoldActive());
                field.applyItalic();
                assertTrue("italic should arm for typing", field.isItalicActive());
                field.applyUnderline();
                assertTrue("underline should arm for typing", field.isUnderlineActive());

                field.applyBulletList();
                assertTrue("bullet should apply", field.isBulletActive());
            }
        });
    }
}
