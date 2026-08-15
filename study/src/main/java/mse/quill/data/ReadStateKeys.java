package mse.quill.data;

/**
 * The names on the wire for what the phone's voice is doing — the state behind the watch's
 * transport controls.
 *
 * <p>The opposite direction and the opposite kind from {@link ReadControlKeys}: a command is an
 * event and travels as a message, whereas this is a state the watch should converge on and travels
 * as a {@code DataItem}. One fixed path, overwritten each time, because only the latest answer is
 * of any interest — a watch that missed three updates wants the current one, not all four.
 *
 * <p>Published on every change the phone's own now-playing bar would redraw for, so a reading
 * paused on the phone shows as paused on the wrist without the watch having asked.
 */
public final class ReadStateKeys {

    private ReadStateKeys() {}

    /** The single {@code DataItem} path the phone publishes read state on. */
    public static final String PATH = "/quill/read-state";

    /**
     * Whether a note is being read at all — paused counts as active.
     *
     * <p>The watch's controls exist only while this is true, and its transport screen closes itself
     * when it goes false. Absent the whole item, the phone has never read anything on this pairing,
     * which the watch treats the same as inactive.
     */
    public static final String KEY_ACTIVE = "active";

    /** Whether the voice is actually speaking, as opposed to paused mid-note. */
    public static final String KEY_PLAYING = "playing";

    /** What the note is called, for the watch to name what it is controlling. */
    public static final String KEY_TITLE = "title";

    /** How far through, 0..1. Chunk-granular — see the phone's reader. */
    public static final String KEY_PROGRESS = "progress";

    /**
     * When this state was published, epoch millis by the phone's clock.
     *
     * <p>Present so that two states that differ in nothing else still differ: the Data Layer drops
     * a put whose bytes match what is already stored, and without this a reading stopped and
     * restarted on the same note would not reach a watch that was already showing it.
     */
    public static final String KEY_UPDATED_AT = "updated_at";
}
