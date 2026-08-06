package mse.quill.ui.audio;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import mse.quill.R;
import mse.quill.audio.AudioPlayback;
import mse.quill.audio.ReadAloud;
import mse.quill.audio.WaveformCache;

/**
 * What Quill is playing, across the top of every screen.
 *
 * <p>It sits in the layout above the nav host rather than floating over it. Floating cost nothing
 * in height but covered whatever was underneath — including the controls at the bottom of a screen,
 * which is the one thing a persistent bar must never do. Taking a row of its own means every screen
 * below it simply starts lower while something is playing.
 *
 * <p>Two things can be playing and they render slightly differently: a recording gets its waveform,
 * which doubles as a scrubber, and a running time; a note being read aloud gets a plain progress
 * bar, because there is no waveform to draw and nowhere meaningful to seek to. Everything else —
 * the title, play/pause, close — is the same control for both.
 *
 * <p>It decides nothing. It reflects {@link AudioPlayback} and {@link ReadAloud} and forwards taps,
 * so it can never disagree with the note's own audio card about what is playing.
 */
public class MiniPlayerView extends LinearLayout
        implements AudioPlayback.Listener, ReadAloud.Listener {

    private final MaterialButton toggleButton;
    private final WaveformBarsView waveform;
    private final LinearProgressIndicator readingProgress;
    private final TextView titleLabel;
    private final TextView timeLabel;

    private String renderedClip;
    private VisibilityListener visibilityListener;

    /** Told whenever the bar appears or disappears, so the activity can hand the status-bar inset
     *  to whichever of the two is currently the topmost thing on screen. */
    public interface VisibilityListener {
        void onMiniPlayerVisibilityChanged(boolean visible);
    }

    public MiniPlayerView(Context context) {
        this(context, null);
    }

    public MiniPlayerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.view_mini_player, this, true);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundResource(R.drawable.bg_mini_player);
        int paddingH = getResources().getDimensionPixelSize(R.dimen.mini_player_padding_horizontal);
        int paddingV = getResources().getDimensionPixelSize(R.dimen.mini_player_padding_vertical);
        setPadding(paddingH, paddingV, paddingH, paddingV);

        toggleButton = findViewById(R.id.mini_player_toggle);
        waveform = findViewById(R.id.mini_player_waveform);
        readingProgress = findViewById(R.id.mini_player_progress);
        titleLabel = findViewById(R.id.mini_player_title);
        timeLabel = findViewById(R.id.mini_player_time);

        toggleButton.setOnClickListener(v -> {
            if (readingIsShowing()) {
                ReadAloud.togglePause();
                return;
            }
            AudioPlayback playback = AudioPlayback.get(getContext());
            if (playback.isPlaying()) playback.pause(); else playback.resume();
        });
        findViewById(R.id.mini_player_close).setOnClickListener(v -> {
            if (readingIsShowing()) {
                ReadAloud.stop();
                return;
            }
            AudioPlayback.get(getContext()).close();
        });
        waveform.setSeekListener(fraction -> {
            AudioPlayback playback = AudioPlayback.get(getContext());
            playback.seekTo(Math.round(fraction * playback.durationMs()));
        });
    }

    public void setVisibilityListener(VisibilityListener listener) {
        this.visibilityListener = listener;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        AudioPlayback.get(getContext()).addListener(this);
        ReadAloud.addListener(this);
        render();
    }

    @Override
    protected void onDetachedFromWindow() {
        AudioPlayback.get(getContext()).removeListener(this);
        ReadAloud.removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onPlaybackStateChanged() {
        render();
    }

    @Override
    public void onReadAloudChanged() {
        render();
    }

    @Override
    public void onPlaybackProgress(int positionMs) {
        if (readingIsShowing()) return;
        AudioPlayback playback = AudioPlayback.get(getContext());
        int total = Math.max(1, playback.durationMs());
        waveform.setProgress(positionMs / (float) total);
        timeLabel.setText(PlaybackTime.elapsedOfTotal(positionMs, total));
    }

    /** A recording wins the bar if one is loaded. Both outlive the note they came from now, so this
     *  is only a tie-break, and the tie is not supposed to happen: starting either one ends the
     *  other. Preferring the clip keeps the seekable thing on screen if it ever does. */
    private boolean readingIsShowing() {
        return !AudioPlayback.get(getContext()).hasClip() && ReadAloud.isActive();
    }

    private void render() {
        boolean wasVisible = getVisibility() == View.VISIBLE;
        AudioPlayback playback = AudioPlayback.get(getContext());

        if (playback.hasClip()) {
            renderClip(playback);
        } else if (ReadAloud.isActive()) {
            renderReading();
        } else {
            setVisibility(View.GONE);
            renderedClip = null;
        }

        boolean visible = getVisibility() == View.VISIBLE;
        if (visible != wasVisible && visibilityListener != null) {
            visibilityListener.onMiniPlayerVisibilityChanged(visible);
        }
    }

    private void renderClip(AudioPlayback playback) {
        setVisibility(View.VISIBLE);
        waveform.setVisibility(View.VISIBLE);
        readingProgress.setVisibility(View.GONE);
        timeLabel.setVisibility(View.VISIBLE);

        showToggle(playback.isPlaying());
        String title = playback.currentTitle();
        titleLabel.setText(title == null || title.trim().isEmpty()
                ? getContext().getString(R.string.audio_notification_fallback_title) : title);

        int total = Math.max(1, playback.durationMs());
        int position = playback.currentPositionMs();
        waveform.setProgress(position / (float) total);
        timeLabel.setText(PlaybackTime.elapsedOfTotal(position, total));

        // Only re-measure the file when the clip itself changes; play/pause redraws land here too.
        String clip = playback.currentFilePath();
        if (!clip.equals(renderedClip)) {
            renderedClip = clip;
            waveform.setWaveform(null);
            WaveformCache.load(clip, bars -> {
                if (clip.equals(renderedClip)) waveform.setWaveform(bars);
            });
        }
    }

    private void renderReading() {
        setVisibility(View.VISIBLE);
        waveform.setVisibility(View.GONE);
        readingProgress.setVisibility(View.VISIBLE);
        // No clock: a voice has no length until it has finished, and guessing one from character
        // counts would be a number that lies.
        timeLabel.setVisibility(View.GONE);
        renderedClip = null;

        showToggle(ReadAloud.isPlaying());
        String title = ReadAloud.title();
        titleLabel.setText(getContext().getString(R.string.reading_aloud_label,
                title == null || title.trim().isEmpty()
                        ? getContext().getString(R.string.audio_notification_fallback_title) : title));
        readingProgress.setProgress(Math.round(ReadAloud.progress() * 100), true);
    }

    private void showToggle(boolean playing) {
        toggleButton.setIconResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        toggleButton.setContentDescription(getContext().getString(
                playing ? R.string.action_pause_audio : R.string.action_play_audio));
    }
}
