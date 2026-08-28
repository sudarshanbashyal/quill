package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BulletSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import mse.quill.data.serialization.MarkdownSerializer;
import mse.quill.data.model.HeadingMarker;

/** Round-trip coverage for the Spannable ↔ Markdown conversion of a single text segment. */
@RunWith(AndroidJUnit4.class)
public class MarkdownSerializerTest {

    private static SpannableStringBuilder spannable(String text) {
        return new SpannableStringBuilder(text);
    }

    /** Asserts text and all bold/italic/underline/bullet span bounds survive a round trip. */
    private static void assertRoundTrips(Spannable original) {
        String markdown = MarkdownSerializer.toMarkdown(original);
        Spannable restored = MarkdownSerializer.fromMarkdown(markdown);

        assertEquals("text changed (markdown was: " + markdown + ")",
                original.toString(), restored.toString());
        assertEquals("bold spans (markdown was: " + markdown + ")",
                describe(original, StyleSpan.class, Typeface.BOLD),
                describe(restored, StyleSpan.class, Typeface.BOLD));
        assertEquals("italic spans (markdown was: " + markdown + ")",
                describe(original, StyleSpan.class, Typeface.ITALIC),
                describe(restored, StyleSpan.class, Typeface.ITALIC));
        assertEquals("underline spans (markdown was: " + markdown + ")",
                describe(original, UnderlineSpan.class, -1),
                describe(restored, UnderlineSpan.class, -1));
        assertEquals("bullet spans (markdown was: " + markdown + ")",
                describe(original, BulletSpan.class, -1),
                describe(restored, BulletSpan.class, -1));
    }

    private static String describe(Spannable text, Class<?> type, int style) {
        StringBuilder out = new StringBuilder();
        for (Object span : text.getSpans(0, text.length(), type)) {
            if (span instanceof StyleSpan && ((StyleSpan) span).getStyle() != style) continue;
            out.append('[').append(text.getSpanStart(span)).append(',')
                    .append(text.getSpanEnd(span)).append(']');
        }
        return out.toString();
    }

    @Test
    public void plainTextRoundTrips() {
        assertRoundTrips(spannable("Just some ordinary prose."));
    }

    @Test
    public void emptyTextRoundTrips() {
        assertRoundTrips(spannable(""));
    }

    /** The case the old HTML serializer needed a private marker character to survive. */
    @Test
    public void consecutiveBlankLinesRoundTrip() {
        assertRoundTrips(spannable("first\n\n\n\nlast"));
        assertEquals("first\n\n\n\nlast",
                MarkdownSerializer.fromMarkdown(
                        MarkdownSerializer.toMarkdown(spannable("first\n\n\n\nlast"))).toString());
    }

    @Test
    public void trailingAndLeadingNewlinesRoundTrip() {
        assertRoundTrips(spannable("\n\nbody\n\n"));
    }

    @Test
    public void boldItalicUnderlineRoundTrip() {
        SpannableStringBuilder text = spannable("bold italic under plain");
        text.setSpan(new StyleSpan(Typeface.BOLD), 0, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.ITALIC), 5, 11, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new UnderlineSpan(), 12, 17, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertRoundTrips(text);
    }

    @Test
    public void overlappingBoldAndItalicRoundTrip() {
        SpannableStringBuilder text = spannable("all of this is styled");
        text.setSpan(new StyleSpan(Typeface.BOLD), 0, 14, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.ITALIC), 7, 21, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertRoundTrips(text);
    }

    @Test
    public void headingBecomesMarkdownPrefixAndComesBackAsMarker() {
        SpannableStringBuilder text = spannable(HeadingMarker.forLevel(HeadingMarker.H1) + "Title");
        String markdown = MarkdownSerializer.toMarkdown(text);

        assertEquals("# Title", markdown);
        Spannable restored = MarkdownSerializer.fromMarkdown(markdown);
        assertEquals(HeadingMarker.H1, HeadingMarker.levelOf(restored));
        assertEquals("Title", HeadingMarker.strip(restored.toString()));
    }

    @Test
    public void headingLevelTwoRoundTrips() {
        SpannableStringBuilder text = spannable(HeadingMarker.forLevel(HeadingMarker.H2) + "Sub");
        assertEquals("## Sub", MarkdownSerializer.toMarkdown(text));
        assertRoundTrips(text);
    }

    /** The derived bold/size styling on a heading must not be re-encoded, or it would compound. */
    @Test
    public void headingDerivedBoldIsNotEncoded() {
        String marker = HeadingMarker.forLevel(HeadingMarker.H1);
        SpannableStringBuilder text = spannable(marker + "Title");
        text.setSpan(new StyleSpan(Typeface.BOLD), marker.length(), text.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        assertEquals("# Title", MarkdownSerializer.toMarkdown(text));
    }

    @Test
    public void bulletRoundTrips() {
        SpannableStringBuilder text = spannable("one\ntwo");
        text.setSpan(new BulletSpan(24), 0, 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new BulletSpan(24), 4, 7, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        assertEquals("- one\n- two", MarkdownSerializer.toMarkdown(text));
        assertRoundTrips(text);
    }

    @Test
    public void mixedHeadingBulletAndInlineRoundTrip() {
        String marker = HeadingMarker.forLevel(HeadingMarker.H2);
        SpannableStringBuilder text = spannable(marker + "Agenda\nfirst item\nplain line");
        int bulletStart = marker.length() + "Agenda\n".length();
        text.setSpan(new BulletSpan(24), bulletStart, bulletStart + 10,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.BOLD), bulletStart, bulletStart + 5,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertRoundTrips(text);
    }

    // ── Escaping: text the user typed that looks like syntax ────────────────

    @Test
    public void literalAsterisksRoundTrip() {
        assertRoundTrips(spannable("2 * 3 * 4 and **not bold**"));
    }

    /** Underscore is the italic marker, so prose containing one must survive escaping. */
    @Test
    public void literalUnderscoresRoundTrip() {
        assertRoundTrips(spannable("snake_case_name and _not italic_ and a lone _"));
    }

    @Test
    public void italicUsesUnderscoreNotAsterisk() {
        SpannableStringBuilder text = spannable("italic");
        text.setSpan(new StyleSpan(Typeface.ITALIC), 0, 6, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        assertEquals("_italic_", MarkdownSerializer.toMarkdown(text));
    }

    /** Overlapping spans force a close-then-reopen run; it must stay unambiguous. */
    @Test
    public void overlappingSpansProduceNoAmbiguousAsteriskRun() {
        SpannableStringBuilder text = spannable("all of this is styled");
        text.setSpan(new StyleSpan(Typeface.BOLD), 0, 14, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.ITALIC), 7, 21, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        String markdown = MarkdownSerializer.toMarkdown(text);
        assertTrue("no run of 5+ asterisks: " + markdown, markdown.indexOf("*****") < 0);
    }

    @Test
    public void literalBackslashRoundTrips() {
        assertRoundTrips(spannable("C:\\Users\\path and a trailing \\"));
    }

    @Test
    public void literalUnderlineTagRoundTrips() {
        assertRoundTrips(spannable("write <u>tags</u> literally"));
    }

    @Test
    public void lineStartingWithHashIsNotReadBackAsHeading() {
        Spannable original = spannable("#1 priority");
        Spannable restored = MarkdownSerializer.fromMarkdown(MarkdownSerializer.toMarkdown(original));

        assertEquals("#1 priority", restored.toString());
        assertEquals(HeadingMarker.NONE, HeadingMarker.levelOf(restored));
    }

    @Test
    public void lineStartingWithHyphenIsNotReadBackAsBullet() {
        Spannable original = spannable("- not a real bullet");
        Spannable restored = MarkdownSerializer.fromMarkdown(MarkdownSerializer.toMarkdown(original));

        assertEquals("- not a real bullet", restored.toString());
        assertEquals("no bullet span expected", "", describe(restored, BulletSpan.class, -1));
    }

    @Test
    public void hashMidLineNeedsNoEscaping() {
        String markdown = MarkdownSerializer.toMarkdown(spannable("issue #42 filed"));
        assertTrue("mid-line # should not be escaped: " + markdown, markdown.indexOf('\\') < 0);
        assertRoundTrips(spannable("issue #42 filed"));
    }
}
