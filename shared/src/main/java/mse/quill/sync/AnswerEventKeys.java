package mse.quill.sync;
import mse.quill.study.scheduling.FlashcardScheduler;

/**
 * The names on the wire for an answer travelling back from the watch, in one place for the same
 * reason {@link DueProjectionKeys} is: both ends have to agree and neither can see the other's
 * source.
 *
 * <p>This is the return half of the projection. The projection goes out as a {@code DataItem} —
 * one item, replaced wholesale, because the watch holds current state. An answer goes back as a
 * {@code MessageClient} message instead, because it is an <em>event</em>: two answers to the same
 * card are two facts, and a {@code DataItem} keyed by card id would have the second silently
 * overwrite the first.
 *
 * <p>Values, not just names, are frozen — see {@link DueProjectionKeys}. A watch app built against
 * an older path would send answers into a void that logs nothing.
 */
public final class AnswerEventKeys {

    private AnswerEventKeys() {}

    /** The {@code MessageClient} path an answer is sent on. */
    public static final String PATH = "/quill/answer";

    /** Which card was answered. */
    public static final String KEY_CARD_ID = "card_id";

    /**
     * Whether it was recalled. Two values rather than SM-2's 0–5, matching the phone's two-button
     * review exactly — see {@code FlashcardScheduler} for why the six-point scale is not offered.
     */
    public static final String KEY_CORRECT = "correct";

    /**
     * When the answer was given, epoch millis by the <em>watch's</em> clock.
     *
     * <p>Carried rather than assumed, because the phone must schedule from when the card was
     * actually answered and not from when the message arrived. A session answered on a plane and
     * delivered hours later would otherwise anchor every interval to the moment of delivery.
     */
    public static final String KEY_ANSWERED_AT = "answered_at";
}
