package mse.quill.data;

/**
 * The names on the wire for "read this note to me", sent from the watch.
 *
 * <p>A message rather than a {@code DataItem}: this is a command, and a command is an event. The
 * watch is not describing a state the phone should match — it is asking for something to happen
 * once, and asking twice should read a note twice.
 *
 * <p><b>Only the id travels.</b> The phone already holds the text, knows how to flatten it for
 * speech, and owns the {@code TextToSpeech} engine and the media session that will carry it. The
 * watch's role ends at naming the note; sending the body would mean shipping a note back to the
 * device it came from so that device could read it aloud to the room.
 */
public final class ReadRequestKeys {

    private ReadRequestKeys() {}

    /** The {@code MessageClient} path a read request is sent on. */
    public static final String PATH = "/quill/read";

    /** Which note to read. Required — there is no sensible default for "read something". */
    public static final String KEY_NOTE_ID = "note_id";
}
