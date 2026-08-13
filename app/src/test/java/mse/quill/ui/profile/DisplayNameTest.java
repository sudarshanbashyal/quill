package mse.quill.ui.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The display-name rule, tested on the JVM.
 *
 * <p>{@link DisplayName#sanitize} and {@link DisplayName#isAllowed} touch nothing but
 * {@code java.lang.Character}, so the rule can be pinned down here rather than by typing into an
 * emulator — which is also the only practical way to cover the emoji cases, since injected key
 * events can't produce them.
 */
public class DisplayNameTest {

    @Test
    public void keepsLettersDigitsHyphenUnderscore() {
        assertEquals("Sudarshan_B-1", DisplayName.sanitize("Sudarshan_B-1"));
    }

    @Test
    public void stripsPunctuationAndSpaces() {
        assertEquals("SudarshanB", DisplayName.sanitize("Sudarshan B!"));
        assertEquals("Sudrshan", DisplayName.sanitize("Sud@r.sh!an"));
        assertEquals("SudrshanB", DisplayName.sanitize("Sud@rsh.an, B"));
    }

    @Test
    public void keepsNonLatinLetters() {
        // Character.isLetterOrDigit is Unicode-aware, so a name doesn't have to be spellable in
        // ASCII to be a name.
        assertEquals("सुदर्शन", DisplayName.sanitize("सुदर्शन"));
        assertEquals("日本語", DisplayName.sanitize("日本語"));
    }

    @Test
    public void keepsEmoji() {
        assertEquals("Sud😀", DisplayName.sanitize("Sud😀"));
        assertTrue(DisplayName.isAllowed("😀".codePointAt(0)));
        assertTrue(DisplayName.isAllowed("🎨".codePointAt(0)));
    }

    @Test
    public void keepsMultiCodePointEmoji() {
        // A ZWJ sequence and a skin-tone modifier have to survive whole; admitting the base glyph
        // but dropping its joiners would turn one emoji into two unrelated ones.
        String family = "👩‍💻";
        assertEquals(family, DisplayName.sanitize(family));
        String thumbsUp = "👍🏽";
        assertEquals(thumbsUp, DisplayName.sanitize(thumbsUp));
    }

    @Test
    public void rejectsDisallowedCharacters() {
        assertFalse(DisplayName.isAllowed(' '));
        assertFalse(DisplayName.isAllowed('.'));
        assertFalse(DisplayName.isAllowed('!'));
        assertFalse(DisplayName.isAllowed('@'));
        assertFalse(DisplayName.isAllowed('\n'));
    }

    @Test
    public void capsAtTwentyCodePoints() {
        String tooLong = "abcdefghijklmnopqrstuvwxyz";
        assertEquals(DisplayName.MAX_LENGTH, DisplayName.sanitize(tooLong).length());
        assertEquals("abcdefghijklmnopqrst", DisplayName.sanitize(tooLong));
    }

    @Test
    public void countsEmojiAsOneCharacterNotTwo() {
        // Twenty emoji are twenty characters. Counting in chars would charge each surrogate pair
        // twice and cut the name in half.
        StringBuilder twentyEmoji = new StringBuilder();
        for (int i = 0; i < 20; i++) twentyEmoji.append("😀");
        String result = DisplayName.sanitize(twentyEmoji.toString());
        assertEquals(20, result.codePointCount(0, result.length()));
        assertEquals(twentyEmoji.toString(), result);
    }

    @Test
    public void neverTruncatesMidSurrogatePair() {
        // Twenty-one emoji: the cut lands exactly on an emoji boundary, never between the two
        // halves of one — a lone surrogate is not valid text and renders as a replacement glyph.
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 21; i++) input.append("😀");
        String result = DisplayName.sanitize(input.toString());
        assertEquals(40, result.length());          // 20 emoji × 2 chars
        assertFalse(Character.isHighSurrogate(result.charAt(result.length() - 1)));
    }

    @Test
    public void nullStaysNull() {
        assertNull(DisplayName.sanitize(null));
    }

    @Test
    public void allDisallowedBecomesEmpty() {
        assertEquals("", DisplayName.sanitize("!!! ... @@@"));
    }
}
