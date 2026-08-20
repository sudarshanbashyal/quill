package mse.quill.collab;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What the QR code carries, and how a scan is checked before anything is done with it.
 *
 * <p>The code used to be the bare token, which meant any QR at all was a valid session code as far
 * as the joiner was concerned — a poster, a Wi-Fi card, a friend's contact details would all be
 * taken as a session to look for, and the search would then run until the user gave up. A prefixed,
 * versioned string can be told apart from the rest of the world's QR codes in one comparison, which
 * is what turns "nothing is happening" into "that isn't a Quill session".
 *
 * <p>The shape is a URI rather than a bare marker because it is the natural thing to make
 * scannable by anything else later — a phone's own camera app can offer to open a {@code quill://}
 * link, where it can offer nothing at all for a naked token.
 */
public final class SessionCode {

    /** Bumped only if the payload ever carries more than a token. Parsed strictly, so an older
     *  build meeting a newer code says "that isn't a session I understand" rather than guessing. */
    private static final String PREFIX = "quill://whiteboard/join/v1/";

    /** Eight characters of a UUID, uppercased — see {@code CollabSession.host}. */
    private static final Pattern TOKEN = Pattern.compile("^[0-9A-F]{8}$");

    private SessionCode() {}

    /** The string a host's QR code should encode. */
    public static String encode(String token) {
        return PREFIX + token;
    }

    /**
     * The token inside a scanned code, or {@code null} if this was some other QR entirely.
     *
     * <p>A bare token is still accepted, so a phone running this build can join a session hosted by
     * one that predates the prefix. It has to match the token pattern exactly to get that far,
     * which rules out essentially every QR code that isn't one — and a code that slips through now
     * fails against the joiner's timeout rather than hanging.
     */
    public static String parse(String scanned) {
        if (scanned == null) return null;
        String trimmed = scanned.trim();
        if (trimmed.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            trimmed = trimmed.substring(PREFIX.length());
        }
        String token = trimmed.toUpperCase(Locale.ROOT);
        return TOKEN.matcher(token).matches() ? token : null;
    }
}
