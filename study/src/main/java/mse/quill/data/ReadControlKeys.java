package mse.quill.data;

/**
 * The names on the wire for "pause the thing you are reading", sent from the watch.
 *
 * <p>The other half of {@link ReadRequestKeys}. Starting a reading from the wrist and then having
 * to unlock the phone to stop it is worse than not being able to start it there at all — the watch
 * has to be able to finish what it began.
 *
 * <p>A message rather than a {@code DataItem}, and for the same reason the read request is one:
 * this is a command. There is no state here the phone should converge on — the state travels the
 * other way, as {@link ReadStateKeys}. A control that arrives when nothing is being read is a
 * no-op, not an error; the watch may simply have been a moment behind.
 */
public final class ReadControlKeys {

    private ReadControlKeys() {}

    /** The {@code MessageClient} path a transport command is sent on. */
    public static final String PATH = "/quill/read-control";

    /** Which command. One of the {@code ACTION_} constants below. */
    public static final String KEY_ACTION = "action";

    /**
     * Pause if speaking, resume if paused.
     *
     * <p>A toggle rather than separate pause and resume commands, matching the phone's own
     * now-playing bar and its media session. Both ends can disagree about what is currently
     * happening — the state item the watch is drawing from is a fraction of a second old — and a
     * toggle lands correctly either way, where an explicit "pause" sent to a reading that had just
     * paused itself would do nothing and look broken.
     */
    public static final String ACTION_TOGGLE = "toggle";

    /** End the reading. Not a pause: nothing is kept to resume from. */
    public static final String ACTION_STOP = "stop";
}
