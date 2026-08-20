package mse.quill.collab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The rule that decides whether a scan is a session at all. Worth pinning down here rather than on
 * a device: every case below is a QR code somebody could plausibly point the scanner at, and the
 * cost of getting it wrong is the join hanging instead of saying no.
 */
public class SessionCodeTest {

    @Test
    public void encodesAndParsesItsOwnCode() {
        assertEquals("A1B2C3D4", SessionCode.parse(SessionCode.encode("A1B2C3D4")));
    }

    @Test
    public void acceptsABareTokenFromAnOlderHost() {
        assertEquals("A1B2C3D4", SessionCode.parse("A1B2C3D4"));
    }

    @Test
    public void acceptsLowercaseAndSurroundingWhitespace() {
        assertEquals("A1B2C3D4", SessionCode.parse("  a1b2c3d4  "));
    }

    @Test
    public void rejectsEverythingThatIsNotASessionCode() {
        assertNull(SessionCode.parse(null));
        assertNull(SessionCode.parse(""));
        assertNull(SessionCode.parse("https://example.com"));
        assertNull(SessionCode.parse("WIFI:S:MyNetwork;T:WPA;P:hunter2;;"));
        assertNull(SessionCode.parse("BEGIN:VCARD"));
        assertNull(SessionCode.parse("A1B2C3"));          // too short
        assertNull(SessionCode.parse("A1B2C3D4E5"));      // too long
        assertNull(SessionCode.parse("A1B2-C3D4"));       // not the token alphabet
        assertNull(SessionCode.parse("ZZZZZZZZ"));        // right length, not hex
    }

    @Test
    public void rejectsAPrefixWithARubbishToken() {
        assertNull(SessionCode.parse("quill://whiteboard/join/v1/not-a-token"));
    }
}
