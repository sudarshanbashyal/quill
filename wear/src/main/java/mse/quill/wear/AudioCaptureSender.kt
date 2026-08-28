package mse.quill.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mse.quill.sync.AudioCaptureKeys
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Hands one voice memo to the Data Layer, which hands it to the phone whenever it next can.
 *
 * <p>The distinction worth holding onto: [AnswerSender] and [ReadControlSender] send *messages*,
 * which reach a phone that is connected right now or reach nobody at all. This puts an *item*, and
 * a put succeeds against the local store — the phone can be off, out of range, or in another
 * country, and the memo is still going to arrive. That is the right trade for the one thing on this
 * watch there is no second copy of.
 *
 * <p>So "sent" here means stored, not delivered. The screen says so.
 */
class AudioCaptureSender(private val context: Context) {

    /** Queues the recording for the phone, returning whether it was accepted into the store. */
    suspend fun send(
        file: File,
        durationMs: Long,
        recordedAt: Long,
        target: CaptureTarget,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val bytes = try {
                file.readBytes()
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the recording back", e)
                return@withContext false
            }
            if (bytes.isEmpty()) {
                Log.w(TAG, "Refusing to send an empty recording")
                return@withContext false
            }

            // A fresh path per memo, so a second one recorded before the phone woke up for the
            // first does not overwrite it — see AudioCaptureKeys.
            val request = PutDataMapRequest.create(AudioCaptureKeys.pathFor(UUID.randomUUID().toString()))
            request.dataMap.putAsset(AudioCaptureKeys.ASSET_AUDIO, Asset.createFromBytes(bytes))
            request.dataMap.putLong(AudioCaptureKeys.KEY_DURATION_MS, durationMs)
            request.dataMap.putLong(AudioCaptureKeys.KEY_CAPTURED_AT, recordedAt)
            when (target) {
                is CaptureTarget.NewNote ->
                    request.dataMap.putBoolean(AudioCaptureKeys.KEY_NEW_NOTE, true)
                is CaptureTarget.Existing ->
                    request.dataMap.putString(AudioCaptureKeys.KEY_NOTE_ID, target.noteId)
            }

            try {
                // Urgent: the default cadence can sit on a change for minutes, and the user is
                // standing there having just finished talking.
                Tasks.await(
                    Wearable.getDataClient(context).putDataItem(
                        request.asPutDataRequest().setUrgent()
                    ),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS,
                )
                true
            } catch (e: Exception) {
                Log.w(TAG, "Could not queue the recording", e)
                false
            }
        }

    private companion object {
        const val TAG = "QuillMemo"
        const val TIMEOUT_SECONDS = 30L
    }
}

/**
 * Where a memo is going — the two things the picker offers, and nothing else.
 *
 * <p>The inbox was a third case here and is not one any more. It remains the phone's *recovery*
 * destination, for a memo whose chosen note has been deleted or locked since the list was
 * published, but the watch never asks for it by name: a destination the user cannot pick does not
 * need a way to say so on the wire.
 */
sealed interface CaptureTarget {
    /** A note that does not exist yet. The phone creates and names it — see AudioCaptureKeys. */
    data object NewNote : CaptureTarget

    /** One the watch was told about, which may have been deleted or locked since. */
    data class Existing(val noteId: String) : CaptureTarget
}
