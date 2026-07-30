package mse.quill.ui.notes.editor;

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
import java.util.UUID;

/** Reads a note's plain text aloud via Android's on-device TextToSpeech engine. Engine
 *  initialisation is asynchronous, so a speak() call arriving before it's ready is queued and
 *  flushed once {@link TextToSpeech.OnInitListener#onInit} fires.
 *
 *  <p>The engine's own default voice is often its lowest-effort ("robotic") one, so on init this
 *  auto-selects the highest-quality voice available for the current locale that doesn't require a
 *  network round-trip (offline voices are more reliable and stay usable without connectivity) —
 *  unless the user has already picked a specific voice via {@link #setVoice}, which is remembered
 *  across app restarts. */
public class NoteReader {

    private static final String PREFS_NAME = "note_reader_prefs";
    private static final String PREF_VOICE_NAME = "voice_name";

    public interface ReadingListener {
        void onReadingStarted();
        void onReadingFinished();
        void onReadingFailed();
    }

    private final ReadingListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private TextToSpeech tts;
    private boolean ready = false;
    private String pendingText;
    /** Whether <em>this</em> reader has a reading in flight — see {@link #isSpeaking()}. */
    private boolean speaking = false;

    public NoteReader(Context context, ReadingListener listener) {
        this.listener = listener;
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            ready = status == TextToSpeech.SUCCESS;
            if (!ready) {
                boolean hadPending = pendingText != null;
                pendingText = null;
                speaking = false;
                if (hadPending) listener.onReadingFailed();
                return;
            }
            tts.setLanguage(Locale.getDefault());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                // These land on the TTS engine's own thread, not the main thread.
                @Override public void onDone(String utteranceId) {
                    mainHandler.post(() -> {
                        speaking = false;
                        listener.onReadingFinished();
                    });
                }
                @Override public void onError(String utteranceId) {
                    mainHandler.post(() -> {
                        speaking = false;
                        listener.onReadingFailed();
                    });
                }
            });
            restorePreferredVoice();
            if (pendingText != null) {
                String text = pendingText;
                pendingText = null;
                speakInternal(text);
            }
        });
    }

    /** Voices available for the current locale, best quality first — excludes voices that would
     *  need an on-demand download before use. */
    public List<Voice> getAvailableVoices() {
        List<Voice> result = new ArrayList<>();
        if (!ready || tts.getVoices() == null) return result;
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
        return ready ? tts.getVoice() : null;
    }

    /** Selects a voice and remembers it (by name) across app restarts. */
    public void setVoice(Voice voice) {
        if (!ready) return;
        tts.setVoice(voice);
        prefs.edit().putString(PREF_VOICE_NAME, voice.getName()).apply();
    }

    private void restorePreferredVoice() {
        String savedName = prefs.getString(PREF_VOICE_NAME, null);
        Voice target = null;
        if (savedName != null) {
            for (Voice voice : tts.getVoices()) {
                if (voice.getName().equals(savedName)) {
                    target = voice;
                    break;
                }
            }
        }
        if (target == null) target = pickBestOfflineVoice();
        if (target != null) tts.setVoice(target);
    }

    private Voice pickBestOfflineVoice() {
        String language = Locale.getDefault().getLanguage();
        Voice best = null;
        for (Voice voice : tts.getVoices()) {
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
     * global: it is shared by every client in the process and outlives any one of them. A reader is
     * created per note screen, so a freshly opened note would inherit "busy" from the screen before
     * it — the engine can still be settling after a stop, and the docs are explicit that a lag sits
     * between audio being handed to the mixer and playback finishing. The symptom was a note you'd
     * just opened offering to "Stop reading" with nothing playing.
     *
     * <p>Our own flag starts false, which is right by construction: this reader has not been asked
     * to read anything yet.
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
        speaking = true;
        listener.onReadingStarted();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
    }

    /** Halts speech immediately. Unlike reaching the end of an utterance naturally, this does not
     *  trigger onDone/onError — the caller is responsible for updating its own UI state. */
    public void stop() {
        pendingText = null;
        speaking = false;
        if (tts != null) tts.stop();
    }

    public void shutdown() {
        pendingText = null;
        speaking = false;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }
}
