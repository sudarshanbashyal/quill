package mse.quill.audio;

import android.content.Context;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
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
 * <p>A reading is a {@link ReadPlaylist}, not a string: a note's own voice recordings are played
 * where they sit in the document, between the text before them and the text after. So this class is
 * the sequencer over two engines — {@link NoteReader} for words, {@link ClipReader} for recordings —
 * and the place that knows only one of them may be going at a time. Everything the bar and the watch
 * ask about ("is it playing", "how far through") is answered for the reading as a whole, so neither
 * has to know a note had a recording in it.
 *
 * <p>Kept separate from {@link AudioPlayback} rather than folded into it. They look alike in the
 * now-playing bar, but underneath they answer different questions — one is the user playing a
 * recording, with a waveform, a length and somewhere to seek to, the other is a note being performed
 * end to end. What they do share is that only one can be going at a time, and {@link #stopOther} is
 * where that is enforced.
 *
 * <p>Nothing here caches what the engines already know. {@link #isPlaying()} and {@link #progress()}
 * read straight through, so the bar and the note's own menu cannot disagree about whether a voice is
 * speaking. The state of its own is which note is being read, what to call it, and where in the
 * playlist it has got to.
 */
public final class ReadAloud {

    public interface Listener {
        void onReadAloudChanged();
    }

    private static final List<Listener> listeners = new ArrayList<>();

    private static NoteReader reader;
    private static ClipReader clipReader;

    /**
     * Kept so the engines' callbacks can start the next item without a context of their own.
     *
     * <p>The application context, so holding it statically leaks nothing — and the engines it
     * builds are process-wide for the same reason a reading is.
     */
    private static Context appContext;

    private static String noteId;
    private static String title;

    /** The reading in progress, and where it has got to. */
    private static List<ReadPlaylist.Item> items = Collections.emptyList();
    private static int index = -1;
    /** Running total of {@link ReadPlaylist.Item#weightMs()} before each item, plus the whole
     *  reading's weight in the last slot — what {@link #progress()} divides by. */
    private static float[] weightBefore = new float[]{0f};

    /**
     * Whether a reading exists, tracked here rather than asked of an engine.
     *
     * <p>Neither engine can answer it alone: between one item finishing and the next starting, and
     * while a clip is playing, {@link NoteReader#isSpeaking()} is false and the reading is very much
     * still going.
     */
    private static boolean active;
    private static boolean paused;

    private ReadAloud() {}

    /** Created on first use and never released — see {@link NoteReader}'s note on binding cost. */
    private static NoteReader reader(Context context) {
        if (reader == null) {
            reader = new NoteReader(context.getApplicationContext(), new NoteReader.ReadingListener() {
                @Override public void onReadingStarted() { notifyChanged(); }
                @Override public void onReadingProgress() { notifyChanged(); }
                @Override public void onReadingFinished() { advance(); }
                // An engine that can't speak this item shouldn't silence the recordings after it:
                // the reading moves on, exactly as it would from a clip that won't open.
                @Override public void onReadingFailed() { advance(); }
            });
        }
        return reader;
    }

    private static ClipReader clipReader(Context context) {
        if (clipReader == null) {
            clipReader = new ClipReader(context.getApplicationContext(), new ClipReader.Listener() {
                @Override public void onClipFinished() { advance(); }
                @Override public void onClipProgress() { notifyChanged(); }
                @Override public void onClipInterrupted() {
                    paused = true;
                    notifyChanged();
                }
            });
        }
        return clipReader;
    }

    // ── Started by whoever wants a note read ───────────────────────────────

    /**
     * Reads a note aloud, replacing any reading already in progress.
     *
     * @param noteId   which note this is, so the note's own menu can tell "stop the reading I
     *                 started" from "read this other note" — may be null for a note not yet saved,
     *                 in which case {@link #noteIdMinted} fills it in.
     * @param title    what the now-playing bar calls it.
     * @param playlist the note's words and recordings in reading order; an empty one starts
     *                 nothing, which is the right answer for a note with neither.
     */
    public static void start(Context context, String noteId, String title, ReadPlaylist playlist) {
        appContext = context.getApplicationContext();
        stopEngines();
        reset();

        ReadAloud.noteId = noteId;
        ReadAloud.title = title;

        if (playlist == null || playlist.isEmpty()) {
            // Nothing to perform. The title is dropped again so the bar doesn't appear for a
            // reading that never was.
            clear();
            return;
        }

        items = playlist.items();
        weightBefore = weigh(items);
        active = true;
        playFrom(0);
        notifyChanged();
    }

    /**
     * Plays the first item from {@code start} that can be played, and leaves the rest to the
     * engines' callbacks.
     *
     * <p>A loop rather than a recursive "try the next one": a recording whose file has been deleted
     * since the playlist was built is skipped, and a note could hold several of those in a row.
     */
    private static void playFrom(int start) {
        for (int i = start; i < items.size(); i++) {
            index = i;
            ReadPlaylist.Item item = items.get(i);
            if (item.isClip()) {
                if (clipReader(appContext).play(item.filePath, item.durationMs)) return;
            } else {
                // The clip that just ended is released before the voice takes over, so a finished
                // recording isn't left holding a player and the device's audio focus for the rest
                // of the note.
                if (clipReader != null) clipReader.stop();
                reader(appContext).speak(item.text);
                return;
            }
        }
        finish();
    }

    /** One item ended of its own accord — move to the next, or end the reading. */
    private static void advance() {
        // A callback from an engine that was still settling when the reading was stopped, or when
        // another note replaced it: there is nothing left for it to move on to.
        if (!active) return;
        playFrom(index + 1);
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
        return active;
    }

    public static boolean isPlaying() {
        return active && !paused;
    }

    public static String title() { return title; }

    /** Which note the voice is reading, or null for one that has never been saved. Read by
     *  {@code WearReadStatePublisher}, which has to know whose title it is about to publish. */
    public static String noteId() { return noteId; }

    /**
     * How far through the whole note the reading has got, 0..1.
     *
     * <p>Items are weighted by roughly how long they take rather than counted, so a three-minute
     * recording moves the bar three minutes' worth — see {@link ReadPlaylist.Item#weightMs()}.
     */
    public static float progress() {
        if (!active || index < 0 || index >= items.size()) return 0f;
        float total = weightBefore[items.size()];
        if (total <= 0f) return 0f;
        float done = weightBefore[index] + itemProgress() * items.get(index).weightMs();
        return Math.max(0f, Math.min(1f, done / total));
    }

    /** How far through the item being played right now, 0..1 — whichever engine is playing it. */
    private static float itemProgress() {
        if (items.get(index).isClip()) {
            return clipReader == null ? 0f : clipReader.progress();
        }
        return reader == null ? 0f : reader.progress();
    }

    /** Whether the voice you can hear is this note's — the question the editor's menu asks to
     *  decide between offering "Read aloud" and "Stop reading". */
    public static boolean isReadingNote(String candidateNoteId) {
        return isActive() && Objects.equals(noteId, candidateNoteId);
    }

    public static void togglePause() {
        if (!active) return;
        if (paused) resumeCurrent(); else pauseCurrent();
        notifyChanged();
    }

    private static void pauseCurrent() {
        paused = true;
        if (currentIsClip()) {
            if (clipReader != null) clipReader.pause();
        } else if (reader != null) {
            reader.pause();
        }
    }

    private static void resumeCurrent() {
        paused = false;
        if (currentIsClip()) {
            // A clip that can't be picked up again — the speakers went to something else, or the
            // file is gone — is skipped rather than leaving a reading that says it is playing and
            // makes no sound.
            if (clipReader == null || !clipReader.resume()) advance();
        } else if (reader != null) {
            reader.resume();
        }
    }

    private static boolean currentIsClip() {
        return index >= 0 && index < items.size() && items.get(index).isClip();
    }

    public static void stop() {
        stopEngines();
        reset();
        clear();
    }

    /** Ends a reading in progress, for a caller about to start playing something else. Nothing
     *  else can share the speakers sensibly — two voices at once is just noise. */
    public static void stopOther() {
        if (isActive()) stop();
    }

    /** Reached the end of the last item. */
    private static void finish() {
        stopEngines();
        reset();
        clear();
    }

    /** Silences both engines. Neither is created here: a stop with nothing playing must not be
     *  what binds a TTS engine or opens a {@code MediaPlayer}. */
    private static void stopEngines() {
        if (reader != null) reader.stop();
        if (clipReader != null) clipReader.stop();
    }

    private static void reset() {
        active = false;
        paused = false;
        items = Collections.emptyList();
        index = -1;
        weightBefore = new float[]{0f};
    }

    private static void clear() {
        noteId = null;
        title = null;
        notifyChanged();
    }

    /** Prefix sums of the items' weights, with the total in the last slot. */
    private static float[] weigh(List<ReadPlaylist.Item> items) {
        float[] running = new float[items.size() + 1];
        for (int i = 0; i < items.size(); i++) {
            running[i + 1] = running[i] + items.get(i).weightMs();
        }
        return running;
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
        appContext = context.getApplicationContext();
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
