package mse.quill.wear

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * The watch's microphone, wrapped in the one behaviour that matters: it stops when the user says
 * so, and not before.
 *
 * <p>This exists because the system speech recogniser did not. Capture used to launch
 * `ACTION_RECOGNIZE_SPEECH`, which ends the moment you stop making noise — so a pause to think was
 * indistinguishable from being finished, and what the phone received was a transcriber's guess
 * rather than the recording. A memo has neither problem.
 *
 * <p>Encoded small on purpose. AAC at 32kbps, mono, 22.05kHz is voice-grade and nothing more, which
 * is what a wrist microphone can capture anyway; the reason to care is that every recording crosses
 * the Bluetooth link to the phone, where a minute at music bitrates is a minute of waiting.
 */
class MemoRecorder(private val context: Context) {

    /** Where the current recording is being written, or null when idle. */
    private var file: File? = null
    private var recorder: MediaRecorder? = null
    private var startedAt = 0L

    /** Set by the max-duration cutoff so the screen can stop waiting for a tap that won't come. */
    @Volatile
    var stoppedItself = false
        private set

    val isRecording: Boolean get() = recorder != null

    val elapsedMs: Long
        get() = if (isRecording) System.currentTimeMillis() - startedAt else 0L

    /** Latest input level, 0..32767 — what the meter on screen is drawn from. */
    fun amplitude(): Int = try {
        recorder?.maxAmplitude ?: 0
    } catch (e: IllegalStateException) {
        // Between stop() and release() the recorder is alive but no longer measuring.
        0
    }

    /** Begins recording. Returns false if the microphone could not be opened at all. */
    fun start(): Boolean {
        if (isRecording) return true
        stoppedItself = false

        // The cache, not files/: the watch is a staging post. Everything here is either on its way
        // to the phone or already there, and nothing on this device is the only copy for long.
        val directory = File(context.cacheDir, "memos").apply { mkdirs() }
        val destination = File(directory, "memo_${UUID.randomUUID()}.m4a")

        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            created.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(SAMPLE_RATE_HZ)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(destination.absolutePath)
                // A ceiling rather than a target. Nobody means to record for nine minutes; what
                // this catches is a screen that stayed on in a sleeve, and the cost of not
                // catching it is a file that has to cross Bluetooth.
                setMaxDuration(MAX_DURATION_MS)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        // The recorder has already stopped itself by the time this arrives; the
                        // screen polls this flag and finishes the capture as if Stop was tapped.
                        stoppedItself = true
                    }
                }
                prepare()
                start()
            }
            recorder = created
            file = destination
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            // Both the IO kind (no room, bad path) and the state kind (mic held by something else).
            Log.w(TAG, "Could not start recording", e)
            created.release()
            destination.delete()
            recorder = null
            file = null
            false
        }
    }

    /**
     * Ends the recording and returns it, or null if nothing usable was captured.
     *
     * <p>A stop that arrives within a moment of the start throws rather than writing a file — the
     * encoder never got a frame. That is a real case on a watch, where the record and stop targets
     * are the same size and a fumbled tap can hit both.
     */
    fun stop(): Memo? {
        val active = recorder ?: return null
        val destination = file
        val durationMs = elapsedMs

        recorder = null
        file = null

        return try {
            active.stop()
            active.release()
            if (destination == null || !destination.exists() || destination.length() == 0L) {
                destination?.delete()
                null
            } else {
                Memo(destination, durationMs)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Nothing was captured", e)
            active.release()
            destination?.delete()
            null
        }
    }

    /** Throws the recording away — for a screen going away mid-memo, which is not a capture. */
    fun cancel() {
        stop()?.file?.delete()
    }

    /** A finished recording, on its way to the phone and nowhere else. */
    data class Memo(val file: File, val durationMs: Long)

    private companion object {
        const val TAG = "QuillMemo"
        const val SAMPLE_RATE_HZ = 22050
        const val BIT_RATE = 32000
        const val MAX_DURATION_MS = 5 * 60 * 1000
    }
}
