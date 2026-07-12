package mse.quill.data.serialization;

import android.text.Html;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;

import java.nio.charset.StandardCharsets;

/**
 * Round-trips a TextSegmentView's Spannable (StyleSpan BOLD/ITALIC + UnderlineSpan) to/from
 * bytes for storage in note_segments.text_content, using Android's built-in HTML span support
 * instead of a custom binary format.
 *
 * Html.toHtml/fromHtml represent each line as its own <p> paragraph; fromHtml then rejoins
 * paragraphs with a blank line ("\n\n") instead of the original single "\n". We collapse those
 * back down so repeated save/load cycles don't accumulate extra blank lines.
 */
public final class SpanSerializer {

    private SpanSerializer() {}

    public static byte[] toBytes(Spanned spanned) {
        String html = Html.toHtml(spanned, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE);
        return html.getBytes(StandardCharsets.UTF_8);
    }

    public static Spannable fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return new SpannableStringBuilder("");

        String html = new String(bytes, StandardCharsets.UTF_8);
        Spanned parsed = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
        SpannableStringBuilder builder = new SpannableStringBuilder(parsed);

        // Collapse "\n\n" (paragraph joins) back to a single "\n".
        for (int i = builder.length() - 2; i >= 0; i--) {
            if (builder.charAt(i) == '\n' && builder.charAt(i + 1) == '\n') {
                builder.delete(i, i + 1);
            }
        }
        // Html.fromHtml appends a trailing newline for the final paragraph — drop it.
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) == '\n') {
            builder.delete(builder.length() - 1, builder.length());
        }

        return builder;
    }
}
