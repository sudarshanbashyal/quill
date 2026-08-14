package mse.quill.data;

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
}
