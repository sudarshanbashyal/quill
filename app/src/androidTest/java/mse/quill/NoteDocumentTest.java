package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.text.SpannableStringBuilder;

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
