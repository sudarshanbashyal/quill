package mse.quill.ui.profile;

import android.text.InputFilter;

/**
 * What counts as a display name: at most {@link #MAX_LENGTH} characters drawn from letters,
 * digits, spaces, {@code -}, {@code _} and emoji.
 *
 * <p>Space is allowed because this is a display name and not a handle — it is what a whiteboard
 * session shows beside someone's strokes, and "Sudarshan Bashyal" is a name a person has. It was
 * excluded originally, which quietly meant nobody could type their own. {@link #sanitize} collapses
 * runs and trims the ends, so the permission cannot be used to store a name that is mostly nothing.
 *
 * <p>The rule is enforced as an {@link InputFilter} rather than as validation on save, so the
 * field simply refuses the keystroke instead of accepting text and rejecting it later with an
 * error the user has to go back and fix. {@link #sanitize} exists for the other direction — text
 * that arrives without passing through the field, such as a paste handled by the IME or a value
 * restored from preferences written by an older build.
 *
 * <p>Everything is counted and tested in <em>code points</em>, not {@code char}s. An emoji outside
 * the BMP is a surrogate pair, so a {@code char}-based cap would silently charge 😀 twice and — far
 * worse — a truncation could cut between the two halves and leave a lone surrogate, which renders
 * as a replacement glyph and is not valid text.
 */
public final class DisplayName {

    public static final int MAX_LENGTH = 20;

    private DisplayName() {}

    /**
     * Whether one code point may appear in a name.
     *
     * <p>Emoji are admitted by character <em>type</em> rather than by a list of ranges, which would
     * be out of date the next time Unicode adds any. {@code OTHER_SYMBOL} is where the pictographs
     * themselves live; the other three types are the pieces that combine them — the zero-width
     * joiner behind 👩‍💻, the variation selector that asks for the colour form of ❤️, the skin-tone
     * modifiers, the enclosing keycap in 1️⃣. Allowing the base glyph but not its modifiers would
     * accept an emoji and then mangle it.
     */
    public static boolean isAllowed(int codePoint) {
        if (Character.isLetterOrDigit(codePoint)) return true;
        if (codePoint == '-' || codePoint == '_' || codePoint == ' ') return true;

        switch (Character.getType(codePoint)) {
            case Character.OTHER_SYMBOL:      // the pictographs themselves
            case Character.MODIFIER_SYMBOL:   // skin-tone and other modifiers
            case Character.NON_SPACING_MARK:  // variation selectors
            case Character.ENCLOSING_MARK:    // keycaps
            case Character.FORMAT:            // zero-width joiner in multi-part emoji
                return true;
            default:
                return false;
        }
    }

    /**
     * Strips anything {@link #isAllowed} rejects, collapses runs of spaces, trims the ends, and
     * truncates to {@link #MAX_LENGTH}.
     *
     * <p>The space handling is why this does more than filter. A field that accepts spaces accepts
     * "   " as a name, and a truncation that lands on one would store a name with a trailing gap —
     * both of which read as an empty greeting rather than as a name. Trimming happens last, so a
     * name cut short at the limit doesn't keep the space the cut exposed.
     */
    public static String sanitize(String input) {
        if (input == null) return null;
        StringBuilder out = new StringBuilder();
        int kept = 0;
        boolean lastWasSpace = false;
        for (int i = 0; i < input.length() && kept < MAX_LENGTH; ) {
            int codePoint = input.codePointAt(i);
            int width = Character.charCount(codePoint);
            if (isAllowed(codePoint)) {
                boolean isSpace = codePoint == ' ';
                // A leading space, or a second one in a row, is dropped rather than counted —
                // otherwise it eats from the same budget the actual name needs.
                if (!isSpace || (kept > 0 && !lastWasSpace)) {
                    out.appendCodePoint(codePoint);
                    kept++;
                    lastWasSpace = isSpace;
                }
            }
            i += width;
        }
        // Only the end can be left dangling: leading and repeated spaces were never appended.
        while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
            out.deleteCharAt(out.length() - 1);
        }
        return out.toString();
    }

    /**
     * The filter for the name field. Returning null accepts the edit unchanged, which is the
     * common case and worth keeping allocation-free.
     */
    public static InputFilter filter() {
        return (source, start, end, dest, dstart, dend) -> {
            // What survives this edit, in code points: everything already there, less the span
            // being replaced. Both counted the same way — mixing a code-point total with a char
            // range would over-charge a selection that happens to contain an emoji.
            String existing = dest.toString();
            int keptFromDest = existing.codePointCount(0, existing.length())
                    - existing.codePointCount(dstart, dend);
            int roomLeft = MAX_LENGTH - keptFromDest;
            if (roomLeft <= 0) return "";

            StringBuilder accepted = new StringBuilder();
            int taken = 0;
            boolean changed = false;
            for (int i = start; i < end; ) {
                int codePoint = Character.codePointAt(source, i);
                int width = Character.charCount(codePoint);
                if (!isAllowed(codePoint)) {
                    changed = true;
                } else if (taken >= roomLeft) {
                    changed = true;
                } else {
                    accepted.appendCodePoint(codePoint);
                    taken++;
                }
                i += width;
            }
            return changed ? accepted.toString() : null;
        };
    }
}
