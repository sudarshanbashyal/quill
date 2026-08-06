package mse.quill.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Reads a note's plain text aloud via Android's on-device TextToSpeech engine. Engine
 *  initialisation is asynchronous, so a speak() call arriving before it's ready is queued and
 *  flushed once {@link TextToSpeech.OnInitListener#onInit} fires.
 *
 *  <p>This is the engine, not the policy: one of these is owned by {@link ReadAloud} for the life
 *  of the process, and nothing else constructs one. It used to be a field on the note editor's
 *  fragment, which is why {@link #shutdown()} and the guards around it exist — a reading died with
 *  the screen that started it. Keeping one bound instead means a reading outlives the note it came
 *  from, and the first read of a session is the only one that pays for binding.
 *
 *  <p>The engine's own default voice is often its lowest-effort ("robotic") one, so on init this
 *  auto-selects the highest-quality voice available for the current locale that doesn't require a
 *  network round-trip (offline voices are more reliable and stay usable without connectivity) —
 *  unless the user has already picked a specific voice via {@link #setVoice}, which is remembered
 *  across app restarts. */
public class NoteReader {

    private static final String PREFS_NAME = "note_reader_prefs";
    private static final String PREF_VOICE_NAME = "voice_name";

    /** Longest chunk handed to the engine in one go. Two reasons for a ceiling: a note with no
     *  full stops in it would otherwise be a single utterance — nothing to pause between — and
     *  engines cap their input length, so a long note used to be silently truncated. */
    private static final int MAX_CHUNK_CHARS = 300;

    public interface ReadingListener {
        void onReadingStarted();
        void onReadingFinished();
        void onReadingFailed();
        /** A chunk finished and the next one started — i.e. {@link #progress()} moved. */
        void onReadingProgress();
    }

    private final ReadingListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private TextToSpeech tts;
    private boolean ready = false;
    private String pendingText;
    /** Whether <em>this</em> reader has a reading in flight — see {@link #isSpeaking()}. */
    private boolean speaking = false;

    /**
     * The note broken into speakable chunks, and how far through them the engine has got.
     *
     * <p>This is what makes pausing possible at all: {@link TextToSpeech} can only be stopped, not
     * paused, so "pause" is a stop that remembers which chunk was next and "resume" is a fresh
     * speak from there. Reading the whole note as one utterance would leave nothing to resume from.
     */
    private final List<String> chunks = new ArrayList<>();
    private int nextChunk = 0;
    private boolean paused = false;
    /** Identifies the current reading, so a callback for a chunk queued before a stop or a restart
     *  can be recognised as stale. */
    private String readingId = UUID.randomUUID().toString();
    /** Set by {@link #shutdown()} once the engine has been released — see {@link #onEngineInit}. */
    private boolean shutDown = false;

    public NoteReader(Context context, ReadingListener listener) {
        this.listener = listener;
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        tts = new TextToSpeech(context.getApplicationContext(), this::onEngineInit);
    }

    /**
     * Configures the engine once it has finished binding.
     *
     * <p>Binding is asynchronous, and slow enough the first time in a process that a note screen
     * can be opened and closed again before it completes — so this can arrive after
     * {@link #shutdown()} has already released the engine, and has to give up rather than carry
     * on. It reads the engine into a local first for the same reason: the field is what shutdown
     * clears, and dereferencing it here was a null pointer on the main thread, i.e. leaving a note
     * quickly enough took the whole app down.
     */
    private void onEngineInit(int status) {
        TextToSpeech engine = tts;
        if (shutDown || engine == null) return;

        ready = status == TextToSpeech.SUCCESS;
        if (!ready) {
            boolean hadPending = pendingText != null;
            pendingText = null;
            speaking = false;
            if (hadPending) listener.onReadingFailed();
            return;
        }
        engine.setLanguage(Locale.getDefault());
        engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {}
            // These land on the TTS engine's own thread, not the main thread.
            @Override public void onDone(String utteranceId) {
                mainHandler.post(() -> onChunkDone(utteranceId));
            }
            @Override public void onError(String utteranceId) {
                mainHandler.post(() -> {
                    speaking = false;
                    paused = false;
                    listener.onReadingFailed();
                });
            }
        });
        restorePreferredVoice(engine);
        if (pendingText != null) {
            String text = pendingText;
            pendingText = null;
            speakInternal(text);
        }
    }

    /** Voices available for the current locale, best quality first — excludes voices that would
     *  need an on-demand download before use. */
    public List<Voice> getAvailableVoices() {
        List<Voice> result = new ArrayList<>();
        if (!isUsable() || tts.getVoices() == null) return result;
        String language = Locale.getDefault().getLanguage();
        for (Voice voice : tts.getVoices()) {
            if (!voice.getLocale().getLanguage().equals(language)) continue;
            if (voice.getFeatures() != null
                    && voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
            result.add(voice);
        }
        result.sort((a, b) -> b.getQuality() - a.getQuality());
        return result;
    }

    public Voice getCurrentVoice() {
        return isUsable() ? tts.getVoice() : null;
    }

    /** Selects a voice and remembers it (by name) across app restarts. */
    public void setVoice(Voice voice) {
        if (!isUsable()) return;
        tts.setVoice(voice);
        prefs.edit().putString(PREF_VOICE_NAME, voice.getName()).apply();
    }

    /** An engine that is bound, initialised, and not yet shut down. */
    private boolean isUsable() {
        return ready && !shutDown && tts != null;
    }

    private void restorePreferredVoice(TextToSpeech engine) {
        // Not every engine reports its voices; the ones that don't leave the default in place.
        Set<Voice> voices = engine.getVoices();
        if (voices == null) return;

        String savedName = prefs.getString(PREF_VOICE_NAME, null);
        Voice target = null;
        if (savedName != null) {
            for (Voice voice : voices) {
                if (voice.getName().equals(savedName)) {
                    target = voice;
                    break;
                }
            }
        }
        if (target == null) target = pickBestOfflineVoice(voices);
        if (target != null) engine.setVoice(target);
    }

    private Voice pickBestOfflineVoice(Set<Voice> voices) {
        String language = Locale.getDefault().getLanguage();
        Voice best = null;
        for (Voice voice : voices) {
            if (voice.isNetworkConnectionRequired()) continue;
            if (!voice.getLocale().getLanguage().equals(language)) continue;
            if (best == null || voice.getQuality() > best.getQuality()) best = voice;
        }
        return best;
    }

    /**
     * Whether this reader is reading — tracked here rather than asked of the engine.
     *
     * <p>{@link TextToSpeech#isSpeaking()} reports the <em>engine service's</em> state, which is
     * global: it is shared by every client in the process and outlives any one of them. Back when a
     * reader was created per note screen, a freshly opened note would inherit "busy" from the screen
     * before it — the engine can still be settling after a stop, and the docs are explicit that a
     * lag sits between audio being handed to the mixer and playback finishing. The symptom was a
     * note you'd just opened offering to "Stop reading" with nothing playing.
     *
     * <p>Our own flag starts false, which is right by construction: this reader has not been asked
     * to read anything yet. A single long-lived reader makes the inherited-state case rarer, not
     * impossible — the engine is still shared with anything else on the device that speaks.
     */
    public boolean isSpeaking() {
        return speaking;
    }

    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) {
            listener.onReadingFailed();
            return;
        }
        if (!ready) {
            // Counts as speaking from here: the request is accepted and will start on its own once
            // the engine is up, so the control has to offer stopping it in the meantime.
            pendingText = text;
            speaking = true;
            return;
        }
        speakInternal(text);
    }

    private void speakInternal(String text) {
        if (!isUsable()) return;
        chunks.clear();
        chunks.addAll(splitIntoChunks(text));
        nextChunk = 0;
        paused = false;
        speaking = true;
        readingId = UUID.randomUUID().toString();
        listener.onReadingStarted();
        speakFromNextChunk();
    }

    /** Queues everything from {@link #nextChunk} on. The first one flushes so a resume can't play
     *  underneath whatever the engine was still holding. */
    private void speakFromNextChunk() {
        if (!isUsable()) return;
        for (int i = nextChunk; i < chunks.size(); i++) {
            int mode = i == nextChunk ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            tts.speak(chunks.get(i), mode, null, utteranceIdFor(i));
        }
    }

    private void onChunkDone(String utteranceId) {
        int finished = indexOfUtterance(utteranceId);
        // A chunk from a reading that has since been stopped or restarted: its id won't match, and
        // acting on it would move a progress bar that belongs to different text.
        if (finished < 0 || finished < nextChunk) return;

        nextChunk = finished + 1;
        if (nextChunk >= chunks.size()) {
            speaking = false;
            paused = false;
            listener.onReadingFinished();
        } else {
            listener.onReadingProgress();
        }
    }

    /** Carries both the position and which reading it belongs to, so chunks still in the engine's
     *  queue when a reading is replaced can be told apart from the new one's. */
    private String utteranceIdFor(int index) {
        return readingId + ":" + index;
    }

    private int indexOfUtterance(String utteranceId) {
        if (utteranceId == null) return -1;
        int separator = utteranceId.indexOf(':');
        if (separator < 0 || !utteranceId.substring(0, separator).equals(readingId)) return -1;
        try {
            return Integer.parseInt(utteranceId.substring(separator + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Stops the voice but keeps the place — {@link #resume()} carries on from the next chunk. */
    public void pause() {
        if (!speaking || paused) return;
        paused = true;
        if (tts != null) tts.stop();
    }

    public void resume() {
        if (!speaking || !paused) return;
        paused = false;
        speakFromNextChunk();
    }

    public boolean isPaused() {
        return paused;
    }

    /** How far through the note the voice has read, 0..1. */
    public float progress() {
        if (chunks.isEmpty()) return 0f;
        return Math.min(1f, nextChunk / (float) chunks.size());
    }

    /** Halts speech immediately. Unlike reaching the end of an utterance naturally, this does not
     *  trigger onDone/onError — the caller is responsible for updating its own UI state. */
    public void stop() {
        pendingText = null;
        speaking = false;
        paused = false;
        chunks.clear();
        nextChunk = 0;
        // Anything already queued belongs to a reading that no longer exists.
        readingId = UUID.randomUUID().toString();
        if (tts != null) tts.stop();
    }

    public void shutdown() {
        pendingText = null;
        speaking = false;
        paused = false;
        ready = false;
        shutDown = true;
        chunks.clear();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }

    /**
     * Splits a note into chunks the engine speaks one at a time — sentence by sentence where the
     * text has sentences, and by words where one runs past {@link #MAX_CHUNK_CHARS}.
     *
     * <p>The seams are where a pause can land, so they follow punctuation and line breaks: stopping
     * mid-sentence and resuming from the top of the next one is how a person would re-find their
     * place.
     */
    private static List<String> splitIntoChunks(String text) {
        List<String> result = new ArrayList<>();
        for (String line : text.split("\n")) {
            for (String sentence : line.split("(?<=[.!?…])\\s+")) {
                addChunk(result, sentence.trim());
            }
        }
        return result;
    }

    private static void addChunk(List<String> result, String sentence) {
        if (sentence.isEmpty()) return;
        if (sentence.length() <= MAX_CHUNK_CHARS) {
            result.add(sentence);
            return;
        }
        StringBuilder current = new StringBuilder();
        for (String word : sentence.split("\\s+")) {
            if (current.length() > 0 && current.length() + 1 + word.length() > MAX_CHUNK_CHARS) {
                result.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) result.add(current.toString());
    }
}
