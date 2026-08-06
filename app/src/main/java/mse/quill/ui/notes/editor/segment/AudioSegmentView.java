package mse.quill.ui.notes.editor.segment;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import mse.quill.MainActivity;
import mse.quill.R;
import mse.quill.audio.AudioPlayback;
import mse.quill.audio.ReadAloud;
import mse.quill.audio.WaveformCache;
import mse.quill.ui.audio.PlaybackTime;
import mse.quill.ui.audio.WaveformBarsView;
import mse.quill.ui.notes.editor.model.NoteSegment;

/**
 * A recording in a note: a full-width card with a play button, the clip's waveform, and how far
 * through it you are.
 *
 * <p>It was a bare {@code ▶} button and a duration next to it, sized to its contents, which read
 * as a stray control rather than as part of the document. A recording is a block like an image is
 * a block, so it takes the full line, and the waveform gives it something to be — you can see
 * where the pauses are and scrub to one.
 *
 * <p>The view owns no player. It asks {@link AudioPlayback} to play its file and listens for what
 * happens, which is what lets the same clip carry on playing after the note is closed and lets
 * this card show the right state when you come back to it mid-clip.
 */
public class AudioSegmentView extends BaseSegmentView implements AudioPlayback.Listener {

    private final MaterialButton playButton;
    private final WaveformBarsView waveform;
    private final TextView timeLabel;
    private final String filePath;
    private final int durationMs;

    /** What the mini player and the lock screen call this clip. Set by the editor from the note's
     *  title, since a recording has no name of its own. */
    private String clipTitle;

    public AudioSegmentView(Context context, String segmentId, String filePath, int durationMs) {
        super(context, segmentId);
        this.filePath = filePath;
        this.durationMs = durationMs;

        LayoutParams params = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int spacing = dimen(R.dimen.audio_block_spacing);
        params.topMargin = spacing;
        params.bottomMargin = spacing;
        setLayoutParams(params);

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundResource(R.drawable.bg_audio_block);
        int padding = dimen(R.dimen.audio_block_padding);
        setPadding(padding, padding, padding, padding);

        // Inflated rather than constructed so it carries the shared play style — a tinted glyph,
        // no disc behind it — and can't drift from the now-playing bar's button.
        playButton = (MaterialButton) LayoutInflater.from(context)
                .inflate(R.layout.view_play_button, this, false);
        playButton.setOnClickListener(v -> {
            // Asked here because this is where the first play in a session happens, and the
            // notification it unlocks is the control the user will want once they leave the note.
            MainActivity.requestPlaybackNotificationPermission(getContext());
            // One voice at a time — a note being read aloud stops when a recording starts.
            ReadAloud.stopOther();
            AudioPlayback.get(getContext()).toggle(filePath, clipTitle, durationMs);
        });
        addView(playButton);

        waveform = new WaveformBarsView(context);
        waveform.setSeekListener(fraction -> {
            AudioPlayback playback = AudioPlayback.get(getContext());
            // Scrubbing a clip that isn't the live one would have nothing to seek, so it starts
            // this clip first and lands the playhead where the finger went down.
            if (!playback.isCurrent(filePath)) {
                ReadAloud.stopOther();
                playback.play(filePath, clipTitle, durationMs);
            }
            playback.seekTo(Math.round(fraction * playback.durationMs()));
        });
        LayoutParams waveParams = new LayoutParams(
                0, dimen(R.dimen.audio_waveform_height), 1f);
        waveParams.setMarginStart(dimen(R.dimen.audio_block_gap));
        waveParams.setMarginEnd(dimen(R.dimen.audio_block_gap));
        addView(waveform, waveParams);

        timeLabel = new TextView(context);
        timeLabel.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        timeLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.audio_time_text_size));
        addView(timeLabel, new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Long press → confirm before deleting (there's no keyboard gesture for this, so it must
        // not be a single accidental long-press away from losing the recording).
        setOnLongClickListener(v -> {
            showDeleteConfirmation();
            return true;
        });

        WaveformCache.load(filePath, waveform::setWaveform);
        render();
    }

    /** Called by the editor so the clip can name itself outside the note. */
    public void setClipTitle(String title) {
        this.clipTitle = title;
        // A title that arrives while this clip is already playing (the note finished loading after
        // playback started) should reach the notification too.
        AudioPlayback playback = AudioPlayback.get(getContext());
        if (playback.isCurrent(filePath)) playback.retitle(title);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        AudioPlayback.get(getContext()).addListener(this);
        render();
    }

    @Override
    protected void onDetachedFromWindow() {
        AudioPlayback.get(getContext()).removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onPlaybackStateChanged() {
        render();
    }

    @Override
    public void onPlaybackProgress(int positionMs) {
        AudioPlayback playback = AudioPlayback.get(getContext());
        if (!playback.isCurrent(filePath)) return;
        int total = Math.max(1, playback.durationMs());
        waveform.setProgress(positionMs / (float) total);
        timeLabel.setText(PlaybackTime.elapsedOfTotal(positionMs, total));
    }

    /** Draws whatever the player is currently doing with this clip — including nothing, which is
     *  the state every segment except the live one is in. */
    private void render() {
        AudioPlayback playback = AudioPlayback.get(getContext());
        boolean current = playback.isCurrent(filePath);
        boolean playing = current && playback.isPlaying();

        playButton.setIconResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playButton.setContentDescription(getContext().getString(
                playing ? R.string.action_pause_audio : R.string.action_play_audio));

        // Only the loaded clip's waveform is a scrubber. On every other card a hold has to reach
        // this view's long-click listener, which is the only way to delete a recording.
        waveform.setScrubbable(current);

        if (current) {
            int total = Math.max(1, playback.durationMs());
            int position = playback.currentPositionMs();
            waveform.setProgress(position / (float) total);
            timeLabel.setText(PlaybackTime.elapsedOfTotal(position, total));
        } else {
            waveform.setProgress(0f);
            timeLabel.setText(PlaybackTime.format(durationMs));
        }
    }

    private void showDeleteConfirmation() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.delete_audio_title)
                .setMessage(R.string.delete_audio_message)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    if (callback != null) callback.onRequestDelete(this);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private int dimen(int res) {
        return getResources().getDimensionPixelSize(res);
    }

    public String getFilePath() { return filePath; }
    public int getDurationMs() { return durationMs; }

    /** Stops this clip if it is the one playing — the segment is being deleted, so leaving it
     *  playing would leave the mini player offering a recording that no longer exists. */
    public void stopIfPlaying() {
        AudioPlayback.get(getContext()).closeIfCurrent(filePath);
        WaveformCache.forget(filePath);
    }

    @Override
    public int getSegmentType() { return NoteSegment.TYPE_AUDIO; }

    @Override
    public Object getSegmentData() { return filePath; }
}
