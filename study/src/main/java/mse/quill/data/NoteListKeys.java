package mse.quill.data;

/**
 * The names on the wire for the list of notes the watch can pick from.
 *
 * <p>A {@code DataItem} like the due projection, and for the same reason: this is current state,
 * replaced wholesale, not a stream of events. The watch holds it so both the capture and the
 * read-aloud flows can offer a list without asking the phone first — a picker that had to round
 * trip before drawing would be a picker you watch load.
 *
 * <p><b>Titles only.</b> No bodies: the note list exists to be chosen from, and a body is either
 * something the phone will read aloud itself or something the watch is about to append to. Sending
 * bodies would also put the {@code DataItem}'s ~100 KB cap in play for no gain.
 */
public final class NoteListKeys {

    private NoteListKeys() {}

    /** The {@code DataItem} path. One item, replaced whole, like the due projection. */
    public static final String PATH = "/quill/notes";

    /** When the phone built this list, epoch millis. */
    public static final String KEY_GENERATED_AT = "generated_at";

    /**
     * Parallel arrays, matching {@link DueProjectionKeys}'s encoding — {@code DataMap} has typed
     * array support and the decode already knows to take the shorter of the two.
     */
    public static final String KEY_NOTE_IDS = "note_ids";
    public static final String KEY_NOTE_TITLES = "note_titles";

    /**
     * How many notes travel. Titles are small, so this is not a size limit — it is a statement
     * that a watch picker is for the note you were just working on, not for a library. Ordered
     * most-recently-updated first, so the cap drops the ones you are least likely to want.
     */
    public static final int MAX_NOTES = 25;
}
