package mse.quill.data.serialization;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mse.quill.ui.notes.editor.model.HeadingMarker;

/**
 * Round-trips a single text segment's Spannable to/from Markdown.
 *
 * <p>This replaces the old HTML-based SpanSerializer. Because both directions are written here
 * rather than delegated to {@code Html.toHtml}/{@code fromHtml}, newlines survive verbatim — the
 * old serializer's private-marker dance for runs of consecutive blank lines is gone, and so is
 * the risk of a span type silently vanishing because HTML had no representation for it.
 *
 * <p>Supported constructs, matching exactly what the formatting toolbar can produce:
 * <ul>
 *   <li>Heading 1 / 2 — {@code # } / {@code ## } line prefix</li>
 *   <li>Bullet list — {@code - } line prefix (a {@link BulletSpan} over the line)</li>
 *   <li>Bold — {@code **…**}, italic — {@code _…_}, underline — {@code <u>…</u>}</li>
 * </ul>
 *
 * <p>Headings are stored as real Markdown prefixes, but in memory the editor keeps using its
 * zero-width-space line markers (see {@link HeadingMarker}), so this class translates between the
 * two. The size/bold styling of a heading is derived from the marker by the editor on load, so
 * {@code RelativeSizeSpan}s — and the companion bold span covering exactly a heading line — are
 * deliberately not encoded here; re-encoding them would double up on reload.
 */
public final class MarkdownSerializer {

    private static final String H1 = "# ";
    private static final String H2 = "## ";
    private static final String BULLET = "- ";
    private static final String BOLD_MARKER = "**";
    /**
     * Italic is {@code _}, not {@code *}, so that a bold marker and an italic marker can never
     * merge into an ambiguous run of asterisks. Where one format ends inside another, the encoder
     * has to close and reopen at the boundary (Markdown demands proper nesting), which puts a
     * close run directly against an open run: with {@code *} for italic that produces {@code ****}
     * or {@code *****}, which no reader can split back the way it was meant. Mixing the two marker
     * characters keeps every such run unambiguous.
     */
    private static final String ITALIC_MARKER = "_";
    private static final String UNDERLINE_OPEN = "<u>";
    private static final String UNDERLINE_CLOSE = "</u>";
    private static final int BULLET_GAP_WIDTH = 24;

    /** Active-format bits, ordered outermost-first so markers always nest validly. */
    private static final int BOLD = 1;
    private static final int ITALIC = 1 << 1;
    private static final int UNDERLINE = 1 << 2;

    private MarkdownSerializer() {}

    // ── Encode ─────────────────────────────────────────────────────────────

    public static String toMarkdown(Spanned text) {
        StringBuilder out = new StringBuilder();
        int length = text.length();
        int lineStart = 0;

        while (true) {
            int lineEnd = lineStart;
            while (lineEnd < length && text.charAt(lineEnd) != '\n') lineEnd++;

            encodeLine(text, lineStart, lineEnd, out);

            if (lineEnd >= length) break;
            out.append('\n');
            lineStart = lineEnd + 1;
        }
        return out.toString();
    }

    private static void encodeLine(Spanned text, int lineStart, int lineEnd, StringBuilder out) {
        int headingLevel = HeadingMarker.levelOf(text, lineStart, lineEnd);
        int contentStart = lineStart + HeadingMarker.length(headingLevel);

        boolean prefixed = true;
        if (headingLevel == HeadingMarker.H1) {
            out.append(H1);
        } else if (headingLevel == HeadingMarker.H2) {
            out.append(H2);
        } else if (hasBullet(text, lineStart, lineEnd)) {
            out.append(BULLET);
        } else {
            prefixed = false;
        }

        // An unstyled line that happens to *begin* with '#' or '-' would read back as a heading or
        // bullet the user never applied, so escape it. Only matters at line start; both characters
        // are ordinary text anywhere else.
        if (!prefixed && contentStart < lineEnd) {
            char first = text.charAt(contentStart);
            if (first == '#' || first == '-') out.append('\\');
        }

        encodeInline(text, lineStart, contentStart, lineEnd, headingLevel > 0, out);
    }

    private static boolean hasBullet(Spanned text, int lineStart, int lineEnd) {
        return text.getSpans(lineStart, lineEnd, BulletSpan.class).length > 0;
    }

    /**
     * Emits {@code [start, end)} with inline markers. Whenever the set of active formats changes,
     * every open marker is closed (in reverse order) and the new set reopened — more markers than
     * a minimal encoding in some cases, but always correctly nested.
     */
    private static void encodeInline(Spanned text, int lineStart, int start, int end,
                                     boolean isHeading, StringBuilder out) {
        int active = 0;
        int runStart = start;

        for (int i = start; i <= end; i++) {
            int formats = i < end ? formatsAt(text, i, isHeading, lineStart, start, end) : 0;
            if (i < end && formats == active) continue;

            appendEscaped(text, runStart, i, out);
            closeMarkers(active, out);
            if (i < end) openMarkers(formats, out);
            active = formats;
            runStart = i;
        }
    }

    private static int formatsAt(Spanned text, int index, boolean isHeading,
                                 int lineStart, int lineContentStart, int lineEnd) {
        int formats = 0;
        for (StyleSpan span : text.getSpans(index, index + 1, StyleSpan.class)) {
            int spanStart = text.getSpanStart(span);
            if (spanStart > index || text.getSpanEnd(span) <= index) continue;
            // A heading's bold styling is re-derived from the line marker on load, so encoding it
            // would double up. It's identified the same way the editor identifies it for removal:
            // by exactly matching the line's bounds, not by type.
            //
            // Either bound counts as "the whole line". The editor applies its derived span from
            // lineStart — the invisible heading marker included — while a span decoded from a
            // previous save starts after it, at lineContentStart. Accepting only the latter is
            // what used to let the derived bold through: harmless in the app, where a heading is
            // re-bolded from its marker anyway, but it surfaced in an exported file as
            // `# **Heading**`.
            if (isHeading && span.getStyle() == Typeface.BOLD
                    && (spanStart == lineContentStart || spanStart == lineStart)
                    && text.getSpanEnd(span) == lineEnd) {
                continue;
            }
            if (span.getStyle() == Typeface.BOLD) formats |= BOLD;
            if (span.getStyle() == Typeface.ITALIC) formats |= ITALIC;
        }
        for (UnderlineSpan span : text.getSpans(index, index + 1, UnderlineSpan.class)) {
            if (text.getSpanStart(span) <= index && text.getSpanEnd(span) > index) {
                formats |= UNDERLINE;
            }
        }
        return formats;
    }

    private static void openMarkers(int formats, StringBuilder out) {
        if ((formats & BOLD) != 0) out.append(BOLD_MARKER);
        if ((formats & ITALIC) != 0) out.append(ITALIC_MARKER);
        if ((formats & UNDERLINE) != 0) out.append(UNDERLINE_OPEN);
    }

    private static void closeMarkers(int formats, StringBuilder out) {
        if ((formats & UNDERLINE) != 0) out.append(UNDERLINE_CLOSE);
        if ((formats & ITALIC) != 0) out.append(ITALIC_MARKER);
        if ((formats & BOLD) != 0) out.append(BOLD_MARKER);
    }

    /**
     * Escapes only what the decoder below actually interprets — backslash, asterisk, underscore,
     * and a literal {@code <u>}/{@code </u>} — so ordinary prose stays readable in the stored
     * file. An escaped underscore still renders as a plain one in any Markdown reader, so
     * {@code snake\_case} costs readability of the raw file but nothing downstream.
     * Line-leading {@code #}/{@code -} are handled by {@link #encodeLine} instead, since whether
     * they need escaping depends on the line's block prefix.
     */
    private static void appendEscaped(Spanned text, int start, int end, StringBuilder out) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '*' || c == '_') {
                out.append('\\');
            } else if (c == '<' && (matchesAt(text, i, UNDERLINE_OPEN, end)
                    || matchesAt(text, i, UNDERLINE_CLOSE, end))) {
                out.append('\\');
            }
            out.append(c);
        }
    }

    private static boolean matchesAt(CharSequence text, int index, String token, int limit) {
        if (index + token.length() > limit) return false;
        for (int i = 0; i < token.length(); i++) {
            if (text.charAt(index + i) != token.charAt(i)) return false;
        }
        return true;
    }

    // ── Decode ─────────────────────────────────────────────────────────────

    public static Spannable fromMarkdown(String markdown) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (markdown == null || markdown.isEmpty()) return out;

        String[] lines = markdown.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            decodeLine(lines[i], out);
        }
        return out;
    }

    private static void decodeLine(String line, SpannableStringBuilder out) {
        int lineStart = out.length();
        boolean bullet = false;
        int headingLevel = 0;

        if (line.startsWith(H2)) {
            headingLevel = 2;
            line = line.substring(H2.length());
        } else if (line.startsWith(H1)) {
            headingLevel = 1;
            line = line.substring(H1.length());
        } else if (line.startsWith(BULLET)) {
            bullet = true;
            line = line.substring(BULLET.length());
        }

        if (headingLevel > 0) out.append(HeadingMarker.forLevel(headingLevel));

        decodeInline(line, out);

        if (bullet) {
            out.setSpan(new BulletSpan(BULLET_GAP_WIDTH), lineStart, out.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** Mirrors {@link #encodeInline} — markers toggle, and a backslash escapes the next char. */
    private static void decodeInline(String line, SpannableStringBuilder out) {
        List<int[]> openStack = new ArrayList<>(); // {format bit, start offset in `out`}
        List<int[]> ranges = new ArrayList<>();    // {format bit, start, end}
        int i = 0;

        while (i < line.length()) {
            char c = line.charAt(i);

            if (c == '\\' && i + 1 < line.length()) {
                out.append(line.charAt(i + 1));
                i += 2;
            } else if (line.startsWith(BOLD_MARKER, i)) {
                toggle(BOLD, openStack, ranges, out.length());
                i += BOLD_MARKER.length();
            } else if (line.startsWith(ITALIC_MARKER, i)) {
                toggle(ITALIC, openStack, ranges, out.length());
                i += ITALIC_MARKER.length();
            } else if (line.startsWith(UNDERLINE_OPEN, i)) {
                toggle(UNDERLINE, openStack, ranges, out.length());
                i += UNDERLINE_OPEN.length();
            } else if (line.startsWith(UNDERLINE_CLOSE, i)) {
                toggle(UNDERLINE, openStack, ranges, out.length());
                i += UNDERLINE_CLOSE.length();
            } else {
                out.append(c);
                i += 1;
            }
        }

        // Unterminated markers (hand-edited or truncated file) just style to end of line.
        while (!openStack.isEmpty()) {
            int[] open = openStack.remove(openStack.size() - 1);
            ranges.add(new int[]{open[0], open[1], out.length()});
        }

        for (int[] range : coalesce(ranges)) {
            applySpan(range[0], range[1], range[2], out);
        }
    }

    private static void toggle(int format, List<int[]> openStack, List<int[]> ranges, int offset) {
        for (int i = openStack.size() - 1; i >= 0; i--) {
            if (openStack.get(i)[0] == format) {
                int[] open = openStack.remove(i);
                ranges.add(new int[]{format, open[1], offset});
                return;
            }
        }
        openStack.add(new int[]{format, offset});
    }

    /**
     * Joins abutting ranges of the same format into one.
     *
     * <p>Markdown requires properly nested markers, so a span that merely overlaps another — bold
     * over "a b", italic over "b c" — can't be written as one run: the encoder has to close and
     * reopen the outer format at the boundary. Decoding that literally would hand the editor two
     * touching bold spans where it previously had one, and the fragmentation would compound on
     * every save. Rejoining them here keeps a save/load cycle structurally identical, not just
     * visually.
     */
    private static List<int[]> coalesce(List<int[]> ranges) {
        List<int[]> sorted = new ArrayList<>(ranges);
        Collections.sort(sorted, (a, b) ->
                a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));

        List<int[]> merged = new ArrayList<>();
        for (int[] range : sorted) {
            if (!merged.isEmpty()) {
                int[] last = merged.get(merged.size() - 1);
                if (last[0] == range[0] && last[2] >= range[1]) {
                    last[2] = Math.max(last[2], range[2]);
                    continue;
                }
            }
            merged.add(new int[]{range[0], range[1], range[2]});
        }
        return merged;
    }

    private static void applySpan(int format, int start, int end, SpannableStringBuilder out) {
        if (start >= end) return;
        Object span;
        if (format == BOLD) {
            span = new StyleSpan(Typeface.BOLD);
        } else if (format == ITALIC) {
            span = new StyleSpan(Typeface.ITALIC);
        } else {
            span = new UnderlineSpan();
        }
        out.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
