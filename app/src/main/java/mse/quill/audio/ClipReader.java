package mse.quill.audio;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;

/**
 * Plays the recordings inside a note that is being read aloud.
 *
 * <p>This is to a reading's clips what {@link NoteReader} is to its words: the engine, owned by
 * {@link ReadAloud} for the life of the process, driven one item at a time.
 *
 * <p>Deliberately not {@link AudioPlayback}. That player <em>is</em> the answer to "the user is
 * listening to a recording": it owns the now-playing bar's waveform, the foreground service and the
 * lock-screen card, and the note's own audio cards draw themselves from it. A recording heard part
 * way through a reading is not that — it is one item of a performance the bar is already
 * describing, and routing it through {@code AudioPlayback} would have the bar flip identity mid
 * note and its ✕ end something other than what it appears to. The cost is that the note's audio
 * card doesn't animate while the reading plays its clip; the bar's progress covers the whole
 * reading, which is the thing being controlled.
 */
final class ClipReader {

    /** How often the reading's progress is refreshed while a clip plays — the same cadence
     *  {@link AudioPlayback} moves its playhead at. */
    private static final long TICK_MS = 200;

    interface Listener {
        /** The clip reached its end, and the reading should move on. */
        void onClipFinished();
        /** The playhead moved — only interesting because the reading's progress bar is drawn
         *  from it. */
        void onClipProgress();
        /** A call, or another app taking the speakers. The reading pauses rather than talking
         *  underneath, and the user resumes it deliberately. */
        void onClipInterrupted();
    }

    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioFocus focus;

    private MediaPlayer player;
    private int durationMs;
    private boolean playing;

    /**
     * Which clip the callbacks in flight belong to.
     *
     * <p>Bumped by every {@link #play} and {@link #stop}, so a completion posted by a player that
     * has since been released — the reading moved on, or was stopped — can be recognised as stale.
     * Acting on one would skip an item nobody was listening to.
     */
    private int generation;

    /** Built in the constructor rather than here: it reads {@link #listener}, which a field
     *  initialiser is not allowed to assume has been assigned yet. */
    private final AudioManager.OnAudioFocusChangeListener focusListener;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!playing || player == null) return;
            listener.onClipProgress();
            handler.postDelayed(this, TICK_MS);
        }
    };

    ClipReader(Context context, Listener listener) {
        this.listener = listener;
        this.focus = new AudioFocus(context, handler);
        this.focusListener = change -> {
            if (change != AudioManager.AUDIOFOCUS_LOSS
                    && change != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    && change != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) return;
            if (!playing) return;
            pause();
            listener.onClipInterrupted();
        };
    }

    /**
     * Starts a recording.
     *
     * @return false if it can't be played — a file deleted since the playlist was built, or focus
     *         refused. The caller decides what that means; nothing has been started either way.
     */
    boolean play(String path, int fallbackDurationMs) {
        release();
        generation++;
        int thisGeneration = generation;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(AudioFocus.speechAttributes());
            player.setDataSource(path);
            player.prepare();
            player.setOnCompletionListener(mp -> {
                playing = false;
                stopTicker();
                // Posted, not run inline: this is MediaPlayer calling us, and what the reading does
                // next is release the very player delivering the callback.
                handler.post(() -> {
                    if (thisGeneration == generation) listener.onClipFinished();
                });
            });
            int reported = player.getDuration();
            durationMs = reported > 0 ? reported : fallbackDurationMs;
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            release();
            return false;
        }
        if (!focus.request(focusListener)) {
            release();
            return false;
        }
        player.start();
        playing = true;
        startTicker();
        return true;
    }

    void pause() {
        if (player == null || !playing) return;
        player.pause();
        playing = false;
        stopTicker();
        focus.abandon(focusListener);
    }

    /** @return false if there is nothing to resume, or the speakers are no longer ours. */
    boolean resume() {
        if (player == null || playing) return false;
        if (!focus.request(focusListener)) return false;
        player.start();
        playing = true;
        startTicker();
        return true;
    }

    void stop() {
        generation++;
        release();
    }

    boolean hasClip() {
        return player != null;
    }

    boolean isPlaying() {
        return playing;
    }

    /** How far through the current clip the playhead is, 0..1. */
    float progress() {
        if (player == null || durationMs <= 0) return 0f;
        try {
            return Math.min(1f, player.getCurrentPosition() / (float) durationMs);
        } catch (IllegalStateException e) {
            return 0f;
        }
    }

    private void release() {
        stopTicker();
        focus.abandon(focusListener);
        if (player != null) {
            player.release();
            player = null;
        }
        playing = false;
        durationMs = 0;
    }

    private void startTicker() {
        stopTicker();
        handler.postDelayed(ticker, TICK_MS);
    }

    private void stopTicker() {
        handler.removeCallbacks(ticker);
    }
}
