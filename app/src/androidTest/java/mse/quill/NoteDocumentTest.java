package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BulletSpan;
import android.text.style.StyleSpan;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mse.quill.data.serialization.NoteDocument;
import mse.quill.ui.notes.editor.model.AudioSegment;
import mse.quill.ui.notes.editor.model.HeadingMarker;
import mse.quill.ui.notes.editor.model.ImageSegment;
import mse.quill.ui.notes.editor.model.NoteSegment;
import mse.quill.ui.notes.editor.model.QaSegment;
import mse.quill.ui.notes.editor.model.TextSegment;

/** Covers segment list ↔ Markdown document, including embed ordering and asset rejoining. */
@RunWith(AndroidJUnit4.class)
public class NoteDocumentTest {

    private static TextSegment text(String content) {
        return new TextSegment(new SpannableStringBuilder(content));
    }

    private static ImageSegment image(String id, String path) {
        ImageSegment segment = new ImageSegment(path);
        segment.id = id;
        return segment;
    }

    private static AudioSegment audio(String id, String path, int durationMs) {
        AudioSegment segment = new AudioSegment(path, durationMs);
        segment.id = id;
        return segment;
    }

    /** The asset registry the repository would supply, built from the media in `segments`. */
    private static Map<String, NoteSegment> registryFor(List<NoteSegment> segments) {
        Map<String, NoteSegment> assets = new HashMap<>();
        for (NoteSegment segment : segments) {
            if (segment.isMedia()) assets.put(segment.id, segment);
        }
        return assets;
    }

    /** Asserts the segment list survives serialize → parse: same types, text and media in order. */
    private static void assertRoundTrips(List<NoteSegment> original) {
        String markdown = NoteDocument.toMarkdown(original);
        List<NoteSegment> restored = NoteDocument.fromMarkdown(markdown, registryFor(original));

        assertEquals("segment count (markdown was:\n" + markdown + "\n)",
                original.size(), restored.size());
        for (int i = 0; i < original.size(); i++) {
            NoteSegment before = original.get(i);
            NoteSegment after = restored.get(i);
            assertEquals("type at " + i, before.type(), after.type());
            if (before instanceof TextSegment) {
                assertEquals("text at " + i,
                        ((TextSegment) before).content.toString(),
                        ((TextSegment) after).content.toString());
            } else if (before instanceof QaSegment) {
                assertEquals("qa id at " + i, before.id, after.id);
                assertEquals("question at " + i,
                        ((QaSegment) before).question.toString(),
                        ((QaSegment) after).question.toString());
                assertEquals("answer at " + i,
                        ((QaSegment) before).answer.toString(),
                        ((QaSegment) after).answer.toString());
            } else {
                assertEquals("asset id at " + i, before.id, after.id);
                assertEquals("file path at " + i, before.filePath(), after.filePath());
            }
        }
    }

    @Test
    public void textOnlyNoteRoundTrips() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text("one line\nand another"));
        assertRoundTrips(segments);
    }

    @Test
    public void imageBetweenTextRoundTrips() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text("before"));
        segments.add(image("img-1", "/data/images/a.jpg"));
        segments.add(text("after"));
        assertRoundTrips(segments);
    }

    @Test
    public void audioAndImageOrderIsPreserved() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text("intro"));
        segments.add(audio("aud-1", "/data/audio/a.m4a", 4200));
        segments.add(text("middle"));
        segments.add(image("img-1", "/data/images/a.jpg"));
        segments.add(text("outro"));
        assertRoundTrips(segments);
    }

    @Test
    public void emptyTextSegmentsAroundEmbedsSurvive() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text(""));
        segments.add(image("img-1", "/data/images/a.jpg"));
        segments.add(text(""));
        assertRoundTrips(segments);
    }

    @Test
    public void multiLineTextAroundEmbedStaysOneSegment() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text("a\nb\n\nc"));
        segments.add(image("img-1", "/data/images/a.jpg"));
        segments.add(text("d\ne"));
        assertRoundTrips(segments);
    }

    @Test
    public void embedUsesAssetIdNotFilePath() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(image("img-1", "/data/images/a.jpg"));

        String markdown = NoteDocument.toMarkdown(segments);
        assertEquals("![](quill://image/img-1)", markdown);
        assertTrue("path must not leak into the document", markdown.indexOf("a.jpg") < 0);
    }

    /** Audio metadata has no place in link syntax, so it must come back from the registry. */
    @Test
    public void audioDurationIsRejoinedFromRegistry() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(audio("aud-1", "/data/audio/a.m4a", 4200));

        List<NoteSegment> restored =
                NoteDocument.fromMarkdown(NoteDocument.toMarkdown(segments), registryFor(segments));

        assertEquals(1, restored.size());
        assertEquals(4200, ((AudioSegment) restored.get(0)).durationMs);
    }

    @Test
    public void imageDisplayWidthIsRejoinedFromRegistry() {
        ImageSegment original = image("img-1", "/data/images/a.jpg");
        original.displayWidth = 480;
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(original);

        List<NoteSegment> restored =
                NoteDocument.fromMarkdown(NoteDocument.toMarkdown(segments), registryFor(segments));

        assertEquals(480, ((ImageSegment) restored.get(0)).displayWidth);
    }

    /**
     * A document referencing an asset row that's gone shouldn't render the raw URI as text. The
     * text on either side closes up into a single segment, keeping the invariant that two text
     * segments are never adjacent — the same shape the editor would have produced if the embed
     * had been deleted there.
     */
    @Test
    public void embedWithMissingAssetIsDropped() {
        List<NoteSegment> restored = NoteDocument.fromMarkdown(
                "before\n![](quill://image/vanished)\nafter", new HashMap<>());

        assertEquals(1, restored.size());
        assertEquals(NoteSegment.TYPE_TEXT, restored.get(0).type());
        assertEquals("before\nafter", ((TextSegment) restored.get(0)).content.toString());
    }

    /** Text the user literally typed must not be mistaken for an embed reference. */
    @Test
    public void literalEmbedSyntaxInTextIsEscaped() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text("![](quill://image/img-1)"));
        assertRoundTrips(segments);

        List<NoteSegment> restored = NoteDocument.fromMarkdown(
                NoteDocument.toMarkdown(segments), new HashMap<>());
        assertEquals(1, restored.size());
        assertEquals(NoteSegment.TYPE_TEXT, restored.get(0).type());
    }

    // ── Q&A blocks ─────────────────────────────────────────────────────────

    private static QaSegment qa(String question, String answer) {
        return new QaSegment(new SpannableStringBuilder(question), new SpannableStringBuilder(answer));
    }

    private static void assertQaRoundTrips(String question, String answer) {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(qa(question, answer));

        String markdown = NoteDocument.toMarkdown(segments);
        List<NoteSegment> restored = NoteDocument.fromMarkdown(markdown, new HashMap<>());

        assertEquals("segment count (markdown was:\n" + markdown + "\n)", 1, restored.size());
        QaSegment result = (QaSegment) restored.get(0);
        assertEquals("question", question, result.question.toString());
        assertEquals("answer", answer, result.answer.toString());
        assertEquals("id (markdown was:\n" + markdown + "\n)", segments.get(0).id, result.id);
    }

    @Test
    public void qaBlockRoundTrips() {
        assertQaRoundTrips("Which country has the most time zones?", "France, with 12.");
    }

    @Test
    public void qaKeepsQuestionAndAnswerSeparate() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(qa("Q text", "A text"));

        String markdown = NoteDocument.toMarkdown(segments);
        assertTrue("expected a fenced quill-qa block, got:\n" + markdown,
                markdown.startsWith("```quill-qa:" + segments.get(0).id + "\n"));
        assertTrue("expected a divider between the two halves:\n" + markdown,
                markdown.contains("\n---\n"));
    }

    /** The id in the fence is what a flashcard's review history hangs off, so it has to outlive an
     *  edit to the block's text — not just a save. */
    @Test
    public void qaIdSurvivesAnEditToItsContent() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(qa("first draft", "answer"));
        String id = segments.get(0).id;

        QaSegment reloaded = (QaSegment) NoteDocument.fromMarkdown(
                NoteDocument.toMarkdown(segments), new HashMap<>()).get(0);
        reloaded.question = new SpannableStringBuilder("second draft");

        List<NoteSegment> edited = new ArrayList<>();
        edited.add(reloaded);
        QaSegment afterEdit = (QaSegment) NoteDocument.fromMarkdown(
                NoteDocument.toMarkdown(edited), new HashMap<>()).get(0);

        assertEquals(id, afterEdit.id);
        assertEquals("second draft", afterEdit.question.toString());
    }

    /** Blocks written before ids were stored still parse — they just get one. */
    @Test
    public void qaFenceWithoutAnIdStillParses() {
        List<NoteSegment> restored = NoteDocument.fromMarkdown(
                "```quill-qa\nquestion\n---\nanswer\n```", new HashMap<>());

        assertEquals(1, restored.size());
        QaSegment result = (QaSegment) restored.get(0);
        assertEquals("question", result.question.toString());
        assertEquals("answer", result.answer.toString());
        assertTrue("should have been given an id", result.id != null && !result.id.isEmpty());
    }

    /** An id-carrying fence typed into prose must stay prose, exactly as the bare one does. */
    @Test
    public void literalFenceWithIdInBodyTextIsEscaped() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text("```quill-qa:abc123"));

        List<NoteSegment> restored = NoteDocument.fromMarkdown(
                NoteDocument.toMarkdown(segments), new HashMap<>());

        assertEquals(1, restored.size());
        assertEquals(NoteSegment.TYPE_TEXT, restored.get(0).type());
        assertEquals("```quill-qa:abc123", ((TextSegment) restored.get(0)).content.toString());
    }

    @Test
    public void multiLineQaRoundTrips() {
        assertQaRoundTrips("line one\nline two", "answer one\n\nanswer two");
    }

    @Test
    public void emptyQaRoundTrips() {
        assertQaRoundTrips("", "");
    }

    @Test
    public void qaBetweenTextKeepsOrder() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text("before"));
        segments.add(qa("question", "answer"));
        segments.add(text("after"));
        assertRoundTrips(segments);
    }

    @Test
    public void qaFormattingSurvives() {
        SpannableStringBuilder question = new SpannableStringBuilder("bold question");
        question.setSpan(new StyleSpan(Typeface.BOLD), 0, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        SpannableStringBuilder answer = new SpannableStringBuilder("bulleted");
        answer.setSpan(new BulletSpan(24), 0, 8, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        List<NoteSegment> segments = new ArrayList<>();
        segments.add(new QaSegment(question, answer));

        QaSegment restored = (QaSegment) NoteDocument.fromMarkdown(
                NoteDocument.toMarkdown(segments), new HashMap<>()).get(0);

        assertEquals(1, restored.question.getSpans(0, 4, StyleSpan.class).length);
        assertEquals(1, restored.answer.getSpans(0, 8, BulletSpan.class).length);
    }

    /** Text inside the block that looks like the fence or the divider must not break parsing. */
    @Test
    public void qaContentThatLooksLikeScaffoldingIsEscaped() {
        assertQaRoundTrips("---", "```");
        assertQaRoundTrips("what does ``` mean?", "a fence\n---\nnot a divider");
    }

    /** And a fence typed into ordinary prose must stay ordinary prose. */
    @Test
    public void literalFenceInBodyTextIsEscaped() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text("```quill-qa"));

        List<NoteSegment> restored = NoteDocument.fromMarkdown(
                NoteDocument.toMarkdown(segments), new HashMap<>());

        assertEquals(1, restored.size());
        assertEquals(NoteSegment.TYPE_TEXT, restored.get(0).type());
        assertEquals("```quill-qa", ((TextSegment) restored.get(0)).content.toString());
    }

    /** A truncated document shouldn't swallow the user's text. */
    @Test
    public void unterminatedQaFenceStillYieldsContent() {
        List<NoteSegment> restored = NoteDocument.fromMarkdown(
                "```quill-qa\nquestion\n---\nanswer", new HashMap<>());

        assertEquals(1, restored.size());
        QaSegment result = (QaSegment) restored.get(0);
        assertEquals("question", result.question.toString());
        assertTrue("answer text should survive, was: " + result.answer,
                result.answer.toString().startsWith("answer"));
    }

    @Test
    public void qaTextIsSearchableAndScaffoldingIsNot() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(qa("capital of France", "Paris"));

        String plain = NoteDocument.toPlainText(NoteDocument.toMarkdown(segments));

        assertTrue("question kept: " + plain, plain.contains("capital of France"));
        assertTrue("answer kept: " + plain, plain.contains("Paris"));
        assertTrue("fence dropped: " + plain, plain.indexOf("quill-qa") < 0);
        assertTrue("divider dropped: " + plain, plain.indexOf("---") < 0);
    }

    // ── Projections ────────────────────────────────────────────────────────

    @Test
    public void plainTextDropsEmbedsAndMarkers() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text(HeadingMarker.forLevel(HeadingMarker.H1) + "Title"));
        segments.add(image("img-1", "/data/images/a.jpg"));
        segments.add(text("body text"));

        String plain = NoteDocument.toPlainText(NoteDocument.toMarkdown(segments));

        assertTrue("heading text kept: " + plain, plain.contains("Title"));
        assertTrue("body kept: " + plain, plain.contains("body text"));
        assertTrue("embed dropped: " + plain, plain.indexOf("quill://") < 0);
        assertTrue("markdown prefix stripped: " + plain, plain.indexOf('#') < 0);
        assertTrue("heading marker stripped: " + plain,
                plain.indexOf(HeadingMarker.forLevel(HeadingMarker.H1)) < 0);
    }

    @Test
    public void previewIsFirstNonEmptyLine() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text("\n\n  \nActual first line\nsecond"));

        assertEquals("Actual first line",
                NoteDocument.toPreview(NoteDocument.toMarkdown(segments)));
    }

    @Test
    public void previewOfEmptyNoteIsEmpty() {
        assertEquals("", NoteDocument.toPreview(""));
        assertEquals("", NoteDocument.toPreview(null));
    }

    @Test
    public void previewSkipsLeadingEmbed() {
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text(""));
        segments.add(image("img-1", "/data/images/a.jpg"));
        segments.add(text("caption"));

        assertEquals("caption", NoteDocument.toPreview(NoteDocument.toMarkdown(segments)));
    }
}
