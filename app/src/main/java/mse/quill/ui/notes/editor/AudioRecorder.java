package mse.quill.ui.notes.editor;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class AudioRecorder {

    public interface RecordingListener {
        void onRecordingStarted();
        void onRecordingFinished(String filePath, int durationMs);
        void onRecordingFailed();
        void onPermissionDenied();
    }

    private final Fragment fragment;
    private final RecordingListener listener;
    private final ActivityResultLauncher<String> permissionLauncher;

    private MediaRecorder recorder;
    private String currentFilePath;
    private long recordingStartTime;
    private boolean isRecording = false;

    public AudioRecorder(Fragment fragment, RecordingListener listener) {
        this.fragment = fragment;
        this.listener = listener;
        permissionLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) startRecordingInternal();
                    else listener.onPermissionDenied();
                }
        );
    }

    public boolean isRecording() { return isRecording; }

    public int getElapsedMs() {
        return isRecording ? (int) (System.currentTimeMillis() - recordingStartTime) : 0;
    }

    /** Latest input amplitude (0..32767), for driving a live waveform display. */
    public int getMaxAmplitude() {
        if (recorder == null) return 0;
        try {
            return recorder.getMaxAmplitude();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    /** Starts recording if idle, or stops and finalises the current recording if already
     *  recording — the toolbar mic button toggles between these two calls. */
    public void toggleRecording() {
        if (isRecording) {
            stopRecording();
        } else if (ContextCompat.checkSelfPermission(fragment.requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startRecordingInternal();
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    /** Discards any in-progress recording without notifying the listener — used when the editor
     *  screen goes away mid-recording so we don't leak a MediaRecorder or save a stray file. */
    public void cancelIfRecording() {
        if (!isRecording) return;
        try {
            recorder.stop();
        } catch (RuntimeException ignored) {
            // No audio data captured yet — nothing to clean up beyond the file itself.
        }
        recorder.release();
        recorder = null;
        isRecording = false;
        if (currentFilePath != null) new File(currentFilePath).delete();
    }

    private void startRecordingInternal() {
        try {
            File file = createPrivateAudioFile();
            currentFilePath = file.getAbsolutePath();

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setOutputFile(currentFilePath);
            recorder.prepare();
            recorder.start();

            recordingStartTime = System.currentTimeMillis();
            isRecording = true;
            listener.onRecordingStarted();
        } catch (IOException | IllegalStateException e) {
            recorder = null;
            isRecording = false;
            listener.onRecordingFailed();
        }
    }

    private void stopRecording() {
        if (recorder == null) return;
        int durationMs = (int) (System.currentTimeMillis() - recordingStartTime);
        String finishedFilePath = currentFilePath;

        try {
            recorder.stop();
        } catch (RuntimeException e) {
            // Stopped too soon to capture any audio — discard rather than save an empty clip.
            recorder.release();
            recorder = null;
            isRecording = false;
            new File(finishedFilePath).delete();
            listener.onRecordingFailed();
            return;
        }
        recorder.release();
        recorder = null;
        isRecording = false;

        listener.onRecordingFinished(finishedFilePath, durationMs);
    }

    private File createPrivateAudioFile() throws IOException {
        File storageDir = new File(fragment.requireContext().getFilesDir(), "audio");
        if (!storageDir.exists()) storageDir.mkdirs();
        return new File(storageDir, "audio_" + UUID.randomUUID() + ".m4a");
    }
}
