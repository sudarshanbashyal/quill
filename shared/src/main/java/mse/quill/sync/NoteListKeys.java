package mse.quill.sync;

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

    /**
     * A {@code MessageClient} path the watch uses to say "rebuild this list now".
     *
     * <p>The list is normally pushed: the phone republishes whenever something changes what should
     * be on it. This is the admission that pushing alone is a promise the phone cannot always keep —
     * a publish can be missed, and the watch has no way to tell a list that is current from one that
     * is a day old, because both look like the same {@code DataItem}.
     *
     * <p>Asking is cheap and the answer arrives on the existing path, so the watch does not wait on
     * a reply: it draws what it has, asks, and redraws if what comes back differs. The cost of
     * being wrong here is a memo filed into a note the user deleted a minute ago.
     */
    public static final String REFRESH_PATH = "/quill/notes-refresh";

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
