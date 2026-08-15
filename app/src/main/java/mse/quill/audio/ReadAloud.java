package mse.quill.audio;

import android.content.Context;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The app's one read-aloud voice, and the reason a note keeps being read after you leave it.
 *
 * <p>This is to {@link NoteReader} what {@link AudioPlayback} is to {@code MediaPlayer}: the engine
 * used to be a field on the note editor's fragment, stopped in {@code onPause} and shut down in
 * {@code onDestroyView}, so a reading died the moment you navigated anywhere. It lives here now, for
 * the life of the process, and the editor is just one of the things that can ask it to speak.
 *
 * <p>Kept separate from {@link AudioPlayback} rather than folded into it. They look alike in the
 * now-playing bar, but underneath they share nothing — one is a file with a waveform, a length and a
 * seekable position, the other is an engine speaking chunks with no notion of either. What they do
 * share is that only one can be going at a time, and {@link #stopOther} is where that is enforced.
 *
 * <p>Nothing here caches what the reader already knows. {@link #isActive()}, {@link #isPlaying()}
 * and {@link #progress()} all read straight through, so the bar and the note's own menu cannot
 * disagree about whether a voice is speaking. The only state of its own is which note is being read
 * and what to call it.
 */
public final class ReadAloud {

    public interface Listener {
        void onReadAloudChanged();
    }

    private static final List<Listener> listeners = new ArrayList<>();

    private static NoteReader reader;
    private static String noteId;
    private static String title;

    private ReadAloud() {}

    /** Created on first use and never released — see {@link NoteReader}'s note on binding cost. */
    private static NoteReader reader(Context context) {
        if (reader == null) {
            reader = new NoteReader(context.getApplicationContext(), new NoteReader.ReadingListener() {
                @Override public void onReadingStarted() { notifyChanged(); }
                @Override public void onReadingProgress() { notifyChanged(); }
                @Override public void onReadingFinished() { clear(); }
                @Override public void onReadingFailed() { clear(); }
            });
        }
        return reader;
    }

    // ── Started by whoever wants a note read ───────────────────────────────

    /**
     * Reads {@code text} aloud, replacing any reading already in progress.
     *
     * @param noteId which note this is, so the note's own menu can tell "stop the reading I
     *               started" from "read this other note" — may be null for a note not yet saved,
     *               in which case {@link #noteIdMinted} fills it in.
     * @param title  what the now-playing bar calls it.
     */
    public static void start(Context context, String noteId, String title, String text) {
        NoteReader engine = reader(context);
        engine.stop();
        ReadAloud.noteId = noteId;
        ReadAloud.title = title;
        // speak() may fail synchronously on empty text, clearing what was just set — so notify
        // after it, when the answer is settled either way.
        engine.speak(text);
        notifyChanged();
    }

    /** Renames the note being read. Its title is editable while the voice is going, and the bar
     *  should not keep showing the name it had when you pressed play. */
    public static void retitle(String newTitle) {
        if (!isActive() || newTitle == null || newTitle.equals(title)) return;
        title = newTitle;
        notifyChanged();
    }

    /** Adopts the id a note is given when it is first saved, so a reading started on an unsaved
     *  note stays recognisable as that note's once it has one. */
    public static void noteIdMinted(String newNoteId) {
        if (isActive() && noteId == null) noteId = newNoteId;
    }

    // ── Read by the bar and by the note's menu ─────────────────────────────

    public static boolean isActive() {
        return reader != null && reader.isSpeaking();
    }

    public static boolean isPlaying() {
        return isActive() && !reader.isPaused();
    }

    public static String title() { return title; }

    /** Which note the voice is reading, or null for one that has never been saved. Read by
     *  {@code WearReadStatePublisher}, which has to know whose title it is about to publish. */
    public static String noteId() { return noteId; }

    public static float progress() {
        return reader == null ? 0f : reader.progress();
    }

    /** Whether the voice you can hear is this note's — the question the editor's menu asks to
     *  decide between offering "Read aloud" and "Stop reading". */
    public static boolean isReadingNote(String candidateNoteId) {
        return isActive() && Objects.equals(noteId, candidateNoteId);
    }

    public static void togglePause() {
        if (!isActive()) return;
        if (reader.isPaused()) reader.resume(); else reader.pause();
        notifyChanged();
    }

    public static void stop() {
        if (reader != null) reader.stop();
        clear();
    }

    /** Ends a reading in progress, for a caller about to start playing something else. Nothing
     *  else can share the speakers sensibly — two voices at once is just noise. */
    public static void stopOther() {
        if (isActive()) stop();
    }

    private static void clear() {
        noteId = null;
        title = null;
        notifyChanged();
    }

    // ── Voices ─────────────────────────────────────────────────────────────
    //
    // The engine is process-wide and so is the chosen voice, so these are here rather than on a
    // reader some screen happens to hold.

    public static List<Voice> availableVoices(Context context) {
        return reader(context).getAvailableVoices();
    }

    public static Voice currentVoice(Context context) {
        return reader(context).getCurrentVoice();
    }

    public static void setVoice(Context context, Voice voice) {
        reader(context).setVoice(voice);
    }

    /** Binds the engine ahead of the first read. Costs nothing if it is already up, and saves the
     *  voice picker from opening empty on a screen where nothing has been spoken yet. */
    public static void warmUp(Context context) {
        reader(context);
    }

    // ── Listeners ──────────────────────────────────────────────────────────

    public static void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private static void notifyChanged() {
        for (Listener listener : new ArrayList<>(listeners)) listener.onReadAloudChanged();
    }
}
