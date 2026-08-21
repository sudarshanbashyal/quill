package mse.quill.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The app's one audio player, and the reason a recording keeps playing after you leave the note.
 *
 * <p>Playback used to live inside {@code AudioSegmentView}: a {@link MediaPlayer} per segment,
 * created on tap and released when the editor was paused. That tied the sound to a view, so
 * navigating away, or even scrolling far enough for the segment to be recycled, killed it.
 * Everything now goes through this one process-wide object, and the views are just controls that
 * ask it questions.
 *
 * <p>The player deliberately lives here rather than inside {@link AudioPlaybackService}. The
 * service's job is to keep the process foregrounded and own the notification and media session —
 * the things playback needs in order to survive a locked screen. Keeping the player itself out of
 * it means a view can read position and state synchronously, with no binding dance, and there is
 * no window during service startup where a tap on play does nothing.
 */
public final class AudioPlayback {

    /** How often the position is broadcast while playing. Fast enough for a moving playhead,
     *  slow enough not to redraw a waveform on every frame. */
    private static final long TICK_MS = 200;

    public interface Listener {
        /** Something other than the position changed: a different clip, or play/pause/stop. */
        void onPlaybackStateChanged();
        /** @param positionMs current playhead, while playing or after a seek. */
        void onPlaybackProgress(int positionMs);
    }

    private static volatile AudioPlayback instance;

    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioFocus focus;
    private final List<Listener> listeners = new ArrayList<>();

    private MediaPlayer player;
    private String filePath;
    private String title;
    private int durationMs;
    private boolean playing;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        // A call, another app's audio, or an alarm: give way rather than talk over it. Transient
        // ducking is treated as a pause too — a voice memo half-heard under something else is
        // worse than one the user resumes deliberately.
        if (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            pause();
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!playing || player == null) return;
            notifyProgress(currentPositionMs());
            handler.postDelayed(this, TICK_MS);
        }
    };

    private AudioPlayback(Context context) {
        this.appContext = context.getApplicationContext();
        this.focus = new AudioFocus(appContext, handler);
    }

    public static AudioPlayback get(Context context) {
        if (instance == null) {
            synchronized (AudioPlayback.class) {
                if (instance == null) instance = new AudioPlayback(context);
            }
        }
        return instance;
    }

    /** The instance if one exists, without creating it — for the service, which must not be the
     *  thing that brings a player into being. */
    public static AudioPlayback peek() {
        return instance;
    }

    // ── State ──────────────────────────────────────────────────────────────

    public boolean isPlaying() { return playing; }
    public boolean hasClip() { return filePath != null; }
    public String currentFilePath() { return filePath; }
    public String currentTitle() { return title; }

    /** True when {@code path} is the clip loaded right now, playing or paused — which is how a
     *  segment knows whether the playhead on screen is its own. */
    public boolean isCurrent(String path) {
        return path != null && path.equals(filePath);
    }

    public int durationMs() {
        return durationMs;
    }

    public int currentPositionMs() {
        if (player == null) return 0;
        try {
            return player.getCurrentPosition();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    // ── Controls ───────────────────────────────────────────────────────────

    /**
     * Plays a clip, or toggles it if it is the one already loaded — tapping play on the segment
     * you are listening to should pause it, not restart it from zero.
     *
     * @param title what the mini player and notification call this clip, normally its note's name.
     */
    public void toggle(String path, String title, int durationMs) {
        if (isCurrent(path) && player != null) {
            if (playing) pause(); else resume();
            return;
        }
        play(path, title, durationMs);
    }

    public void play(String path, String title, int fallbackDurationMs) {
        release();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(AudioFocus.speechAttributes());
            // A recording in a locked collection is encrypted on disk, and MediaPlayer needs
            // random access — so it gets a MediaDataSource over the plaintext in memory rather
            // than a decrypted temp file that would have to be cleaned up. Plaintext clips keep
            // streaming straight from disk.
            android.media.MediaDataSource source = mse.quill.security.MediaFiles.source(path);
            if (source != null) {
                player.setDataSource(source);
            } else if (mse.quill.security.MediaFiles.isEncrypted(path)) {
                // Encrypted and it would not open. Handing MediaPlayer the path here would give it
                // the ciphertext, which it reports as an unhelpful native decoder error; the real
                // cause is almost always the collection key's authentication window having closed
                // since the note was opened, and the answer is to unlock it again.
                throw new IOException("locked media: " + path);
            } else {
                player.setDataSource(path);
            }
            player.prepare();
            player.setOnCompletionListener(mp -> {
                // A finished clip closes itself, bar and notification with it — the same end state
                // as ✕, reached without asking. It used to stay loaded so the bar could offer to
                // replay it, but a bar that outlives its audio is a control for nothing, and the
                // note's own card is where a replay belongs.
                //
                // Posted rather than run inline: this is MediaPlayer calling us, and close()
                // releases the very player delivering the callback. Keyed to the path that
                // finished, because a tap already queued ahead of this post can have started a
                // different clip by the time it runs — closing unconditionally would kill that one.
                playing = false;
                stopTicker();
                notifyProgress(durationMs());
                handler.post(() -> closeIfCurrent(path));
            });
            this.filePath = path;
            this.title = title;
            // MediaPlayer knows the real length; the recorded duration is a wall-clock estimate
            // and is only used if the file won't report one.
            int reported = player.getDuration();
            this.durationMs = reported > 0 ? reported : fallbackDurationMs;
        } catch (IOException | IllegalStateException e) {
            release();
            notifyStateChanged();
            // Said out loud rather than logged. A play button that does nothing is the one outcome
            // the user cannot diagnose, and "nothing happened" is what a lapsed key looks like from
            // the outside.
            Toast.makeText(appContext,
                    appContext.getString(mse.quill.security.MediaFiles.isEncrypted(path)
                            ? mse.quill.R.string.audio_locked_needs_unlock
                            : mse.quill.R.string.audio_playback_failed),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!requestFocus()) {
            release();
            notifyStateChanged();
            return;
        }
        player.start();
        playing = true;
        AudioPlaybackService.start(appContext);
        startTicker();
        notifyStateChanged();
    }

    /** Renames the clip that's loaded. A note's segments are built before its title is known, so
     *  the first play of a just-opened note would otherwise be labelled with nothing. */
    public void retitle(String title) {
        if (title == null || title.equals(this.title)) return;
        this.title = title;
        notifyStateChanged();
    }

    public void pause() {
        if (player == null || !playing) return;
        player.pause();
        playing = false;
        stopTicker();
        abandonFocus();
        notifyStateChanged();
    }

    public void resume() {
        if (player == null || playing) return;
        if (!requestFocus()) return;
        player.start();
        playing = true;
        AudioPlaybackService.start(appContext);
        startTicker();
        notifyStateChanged();
    }

    public void seekTo(int positionMs) {
        if (player == null) return;
        int clamped = Math.max(0, Math.min(durationMs, positionMs));
        player.seekTo(clamped);
        notifyProgress(clamped);
    }

    /** Ends playback entirely and takes the pill and the notification with it — the ✕ action. */
    public void close() {
        release();
        AudioPlaybackService.stop(appContext);
        notifyStateChanged();
    }

    /** Closes only if {@code path} is what's loaded — used when the segment holding a clip is
     *  deleted, so the pill doesn't keep offering a recording that no longer exists. */
    public void closeIfCurrent(String path) {
        if (isCurrent(path)) close();
    }

    private void release() {
        stopTicker();
        abandonFocus();
        if (player != null) {
            player.release();
            player = null;
        }
        playing = false;
        filePath = null;
        title = null;
        durationMs = 0;
    }

    // ── Audio focus ────────────────────────────────────────────────────────

    private boolean requestFocus() {
        return focus.request(focusListener);
    }

    private void abandonFocus() {
        focus.abandon(focusListener);
    }

    // ── Listeners ──────────────────────────────────────────────────────────

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyStateChanged() {
        // Copied because a listener may well remove itself in response (a segment being detached).
        for (Listener listener : new ArrayList<>(listeners)) listener.onPlaybackStateChanged();
        AudioPlaybackService.refresh(appContext);
    }

    private void notifyProgress(int positionMs) {
        for (Listener listener : new ArrayList<>(listeners)) listener.onPlaybackProgress(positionMs);
    }

    private void startTicker() {
        stopTicker();
        handler.post(ticker);
    }

    private void stopTicker() {
        handler.removeCallbacks(ticker);
    }

    /** Routes a notification or lock-screen button back into the player. */
    static void handleAction(Context context, String action) {
        AudioPlayback playback = peek();
        if (playback == null) return;
        if (AudioPlaybackService.ACTION_TOGGLE.equals(action)) {
            if (playback.isPlaying()) playback.pause(); else playback.resume();
        } else if (AudioPlaybackService.ACTION_CLOSE.equals(action)) {
            playback.close();
        }
    }

    static Intent intentFor(Context context, String action) {
        Intent intent = new Intent(context, AudioPlaybackService.class);
        intent.setAction(action);
        return intent;
    }
}
