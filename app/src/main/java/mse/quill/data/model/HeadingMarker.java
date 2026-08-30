package mse.quill.data.model;

/**
 * The invisible zero-width-space prefix that tags a line as a heading while it's being edited.
 *
 * <p>Heading level is carried as text rather than as a span because the editor moves line content
 * around constantly — splitting a segment at the cursor to insert an embed, merging two segments
 * on backspace, copying into a {@code SpannableStringBuilder} for export. A text marker survives
 * all of that for free, whereas a size span has to be re-derived (and can be clobbered) on every
 * such move. The visible {@code RelativeSizeSpan}/bold styling is always recomputed from this
 * marker — see {@code TextSegmentView.restyleHeadingLine}.
 *
 * <p>On disk the heading is a normal Markdown {@code #}/{@code ##} prefix;
 * {@code MarkdownSerializer} translates between the two. H1's marker is a prefix of H2's, so any
 * detection must test the longer marker first.
 */
public final class HeadingMarker {

    public static final int NONE = 0;
    public static final int H1 = 1;
    public static final int H2 = 2;

    private static final String H1_PREFIX = "​";
    private static final String H2_PREFIX = "​​";

    private HeadingMarker() {}

    /** The marker text for a level, or "" for {@link #NONE}. */
    public static String forLevel(int level) {
        if (level == H1) return H1_PREFIX;
        if (level == H2) return H2_PREFIX;
        return "";
    }

    public static int length(int level) {
        return forLevel(level).length();
    }

    /** 0 ({@link #NONE}) if the line isn't a heading, else its level. */
    public static int levelOf(CharSequence line) {
        if (startsWith(line, H2_PREFIX)) return H2;
        if (startsWith(line, H1_PREFIX)) return H1;
        return NONE;
    }

    /** Level of the line spanning {@code [lineStart, lineEnd)} of a larger text. */
    public static int levelOf(CharSequence text, int lineStart, int lineEnd) {
        return levelOf(text.subSequence(lineStart, lineEnd));
    }

    private static boolean startsWith(CharSequence text, String prefix) {
        if (text.length() < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (text.charAt(i) != prefix.charAt(i)) return false;
        }
        return true;
    }

    /** Strips the marker from a line, if it has one. */
    public static String strip(String line) {
        return line.substring(length(levelOf(line)));
    }
}
