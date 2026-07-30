package mse.quill.ui.notes.editor.segment;

import android.content.Context;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.Locale;

import mse.quill.R;
import mse.quill.ui.notes.editor.model.NoteSegment;

public class AudioSegmentView extends BaseSegmentView {

    private final Button playButton;
    private final TextView durationLabel;
    private final String filePath;
    private final int durationMs;

    private MediaPlayer mediaPlayer;

    public AudioSegmentView(Context context, String segmentId, String filePath, int durationMs) {
        super(context, segmentId);
        this.filePath = filePath;
        this.durationMs = durationMs;

        setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(0, 8, 0, 8);

        playButton = new Button(context);
        playButton.setText("▶");
        playButton.setAllCaps(false);
        playButton.setOnClickListener(v -> togglePlayback());
        addView(playButton);

        durationLabel = new TextView(context);
        durationLabel.setText(formatDuration(durationMs));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        labelParams.setMargins(16, 0, 0, 0);
        durationLabel.setLayoutParams(labelParams);
        addView(durationLabel);

        // Tap non-button area to insert text after this segment
        setOnClickListener(v -> {
            if (callback != null) callback.onRequestSplitAt(this, 0);
        });

        // Long press → confirm before deleting (there's no keyboard gesture for this, so it must
        // not be a single accidental long-press away from losing the recording).
        setOnLongClickListener(v -> {
            showDeleteConfirmation();
            return true;
        });
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

    public String getFilePath() { return filePath; }
    public int getDurationMs() { return durationMs; }

    private void togglePlayback() {
        if (mediaPlayer != null) {
            stopPlayback();
            return;
        }
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(filePath);
            mediaPlayer.setOnCompletionListener(mp -> stopPlayback());
            mediaPlayer.prepare();
            mediaPlayer.start();
            playButton.setText("⏸");
        } catch (IOException e) {
            mediaPlayer = null;
            playButton.setText("▶");
        }
    }

    private void stopPlayback() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        playButton.setText("▶");
    }

    /** Stops playback without leaving a dangling MediaPlayer — called when the segment is
     *  removed or the note editor screen is no longer visible. */
    public void stopIfPlaying() {
        if (mediaPlayer != null) stopPlayback();
    }

    private static String formatDuration(int ms) {
        int totalSeconds = ms / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    @Override
    public int getSegmentType() { return NoteSegment.TYPE_AUDIO; }

    @Override
    public Object getSegmentData() { return filePath; }
}
