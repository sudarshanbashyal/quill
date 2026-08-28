package mse.quill.sync;

/**
 * The names on the wire for a voice memo recorded on the watch.
 *
 * <p>This replaced a transcript. The watch used to hand the phone a string from the system
 * recogniser, which was wrong twice over: a recogniser stops the moment you stop talking, so a
 * pause mid-thought ended the capture, and what arrived was the recogniser's guess at what was said
 * rather than the saying of it. A voice memo has neither problem — recording ends when the user says
 * it ends, and what lands in the note is the audio.
 *
 * <p><b>A {@code DataItem} with an {@code Asset}, not a message.</b> Messages are capped at 100KB
 * and, more importantly, are dropped outright when no node is connected — which for a capture is
 * the thought lost. A {@code DataItem} sits in the store until the phone next appears, and an
 * {@code Asset} is the Data Layer's own answer to a payload too big to inline.
 *
 * <p>The path carries a fresh id per recording ({@link #pathFor}), for the reason a message was
 * originally chosen: a fixed path would let a second memo overwrite a first that the phone had not
 * yet woken up for. The phone deletes each item once it has been filed, so the store does not grow.
 */
public final class AudioCaptureKeys {

    private AudioCaptureKeys() {}

    /**
     * Every audio capture lives under here — the phone's listener filters on this prefix, and each
     * item appends its own id. The trailing slash is part of it: without it the prefix would also
     * match a path that merely started with the same letters.
     */
    public static final String PATH_PREFIX = "/quill/audio-capture/";

    /** The path for one recording. {@code captureId} must be unique — a UUID in practice. */
    public static String pathFor(String captureId) {
        return PATH_PREFIX + captureId;
    }

    /** The recording itself: AAC in an MP4 container, mono, voice-rate. See the watch's recorder. */
    public static final String ASSET_AUDIO = "audio";

    /** How long it runs, millis. Sent rather than measured on arrival: the watch already counted. */
    public static final String KEY_DURATION_MS = "duration_ms";

    /**
     * When it was recorded, epoch millis by the watch's clock.
     *
     * <p>Carried for the same reason an answer's timestamp is: a capture can sit in the store for
     * hours waiting for a phone, and the note should be stamped with when the thought happened
     * rather than with when the two devices happened to meet.
     */
    public static final String KEY_CAPTURED_AT = "captured_at";

    /**
     * Which note to append to. Absent when {@link #KEY_NEW_NOTE} is set, and not otherwise.
     *
     * <p>Naming a note that no longer exists — deleted, or in a collection locked since the list
     * was published — lands the memo in the inbox instead. The inbox is not something the watch can
     * ask for; it is where the phone puts a memo it would otherwise have to throw away. Falling
     * back rather than failing is deliberate: the watch chose from a list that may be hours old,
     * and a memo that arrives in the wrong note is recoverable while one the phone refused to store
     * is not.
     */
    public static final String KEY_NOTE_ID = "note_id";

    /**
     * Set when the memo should go into a note that does not exist yet. Overrides
     * {@link #KEY_NOTE_ID}, which the watch does not send alongside it.
     *
     * <p>A flag rather than a sentinel id, because the phone is being asked to do something
     * different rather than to write somewhere different — and because a sentinel that ever
     * collided with a real note id would file a thought into a stranger's note.
     *
     * <p><b>The phone names it, not the watch.</b> A note created this way is stored with an empty
     * title, which is exactly what the phone's own "new note" does: an untitled note carries no
     * title and every list resolves it to "Untitled Note - &lt;date&gt;". Sending a name from the
     * watch would give these notes a shape no other note in the app has.
     */
    public static final String KEY_NEW_NOTE = "new_note";
}
