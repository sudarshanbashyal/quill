package mse.quill.sync;
import mse.quill.study.scheduling.DueProjection;

/**
 * The names on the wire between the phone and the watch, in one place because both ends have to
 * agree and neither can see the other's source.
 *
 * <p>In {@code :study} — a module that knows nothing about the Data Layer — precisely because it is
 * the only code both {@code :app} and {@code :wear} compile against. A key typed twice in two
 * languages is a bug that costs an afternoon: the publish succeeds, the receive succeeds, and the
 * card list comes back empty with nothing in the log to say why.
 *
 * <p>Values, not just names, are frozen: renaming one is a wire-format change, and an old watch app
 * reading a new phone's {@code DataItem} would silently see nothing rather than fail.
 */
public final class DueProjectionKeys {

    private DueProjectionKeys() {}

    /**
     * The {@code DataItem} path. One item, replaced wholesale on every publish, rather than one per
     * deck — the watch then has nothing to merge and no way to hold a half-updated projection.
     */
    public static final String PATH = "/quill/due";

    /** When the phone built this projection, epoch millis — used to spot a stale item. */
    public static final String KEY_GENERATED_AT = "generated_at";

    /** The end-of-day bound the projection was selected against; see {@link DueProjection}. */
    public static final String KEY_HORIZON = "horizon";

    /**
     * Parallel arrays rather than an array of maps: {@code DataMap} supports typed arrays natively,
     * and three flat arrays encode and decode without allocating a map per card.
     */
    public static final String KEY_CARD_IDS = "card_ids";
    public static final String KEY_CARD_FRONTS = "card_fronts";
    public static final String KEY_CARD_BACKS = "card_backs";
    public static final String KEY_CARD_DUE_AT = "card_due_at";

    /**
     * The note each card belongs to, and that note's resolved title — the watch groups its review
     * list by deck, and a deck is a note.
     *
     * <p>Two more parallel arrays rather than a separate deck list keyed by note id: the decode
     * already takes the shortest of the card arrays as its length, so adding to that set is a
     * change the existing "four chances to disagree" guard already covers. A second list would be
     * a second thing that can disagree with the first.
     *
     * <p>Added after the first release of the projection. An older watch reading a newer phone's
     * item simply ignores them; a newer watch reading an older item gets null and falls back —
     * which is why the decode treats them as optional rather than required.
     */
    public static final String KEY_CARD_NOTE_IDS = "card_note_ids";
    public static final String KEY_CARD_NOTE_TITLES = "card_note_titles";
}
