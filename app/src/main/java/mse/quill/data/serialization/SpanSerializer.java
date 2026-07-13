package mse.quill.data.serialization;

import android.text.Html;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;

import java.nio.charset.StandardCharsets;

/**
 * Round-trips a TextSegmentView's Spannable (StyleSpan BOLD/ITALIC + UnderlineSpan, BulletSpan,
 * RelativeSizeSpan-derived headings) to/from bytes for storage in note_segments.text_content,
 * using Android's built-in HTML span support instead of a custom binary format.
 *
 * Html.toHtml represents each line as its own &lt;p&gt; paragraph, which is what lets
 * paragraph-scoped spans like BulletSpan round-trip via &lt;ul&gt;&lt;li&gt; for free. But
 * Html.fromHtml rejoins paragraphs with "\n\n" regardless of how many real newlines separated
 * them, so a run of consecutive blank lines can't be told apart from a single line break just by
 * looking at the decoded output — there's no reliable way to invert it after the fact. Instead,
 * before encoding, every run of consecutive newlines is collapsed to exactly one real '\n' (so
 * Html.toHtml still sees a normal single paragraph boundary — what BulletSpan/heading detection
 * need) and the rest of that run is swapped for a private marker character that Html carries
 * through as ordinary escaped text content rather than interpreting as more paragraph breaks.
 * Decoding reverses both steps: the standard single "\n\n" -> "\n" collapse (now always exactly
 * one pair, since multi-newline runs never reach Html.toHtml as literal newlines), then restoring
 * the markers back to '\n'.
 */
public final class SpanSerializer {

    /** Unicode LINE SEPARATOR — stands in for a newline that shouldn't be treated as an HTML
     *  paragraph boundary. Not a character real note text would contain. */
    private static final char EXTRA_LINE_MARKER = ' ';

    private SpanSerializer() {}

    public static byte[] toBytes(Spanned spanned) {
        SpannableStringBuilder copy = new SpannableStringBuilder(spanned);

        // Within any run of 2+ consecutive newlines, keep exactly the first as a real '\n' and
        // replace the rest with the marker.
        for (int i = copy.length() - 1; i >= 0; i--) {
            if (copy.charAt(i) == '\n') {
                int runEnd = i;
                int runStart = i;
                while (runStart > 0 && copy.charAt(runStart - 1) == '\n') runStart--;
                for (int j = runStart + 1; j <= runEnd; j++) {
                    copy.replace(j, j + 1, String.valueOf(EXTRA_LINE_MARKER));
                }
                i = runStart;
            }
        }

        String html = Html.toHtml(copy, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE);
        return html.getBytes(StandardCharsets.UTF_8);
    }

    public static Spannable fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return new SpannableStringBuilder("");

        String html = new String(bytes, StandardCharsets.UTF_8);
        Spanned parsed = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
        SpannableStringBuilder builder = new SpannableStringBuilder(parsed);

        // Collapse "\n\n" (paragraph joins) back to a single "\n". Always exactly one pair per
        // boundary now, since toBytes() never lets more than one real newline reach Html.toHtml.
        for (int i = builder.length() - 2; i >= 0; i--) {
            if (builder.charAt(i) == '\n' && builder.charAt(i + 1) == '\n') {
                builder.delete(i, i + 1);
            }
        }
        // Html.fromHtml appends a trailing newline for the final paragraph — drop it.
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) == '\n') {
            builder.delete(builder.length() - 1, builder.length());
        }

        // Restore the extra-newline markers.
        for (int i = 0; i < builder.length(); i++) {
            if (builder.charAt(i) == EXTRA_LINE_MARKER) {
                builder.replace(i, i + 1, "\n");
            }
        }

        return builder;
    }
}
