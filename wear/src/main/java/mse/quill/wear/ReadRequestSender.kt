package mse.quill.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mse.quill.sync.ReadRequestKeys
import java.util.concurrent.TimeUnit

/**
 * Asks the phone to read a note aloud.
 *
 * <p>The lightest of the three senders, because the payload is one id: everything that makes a
 * reading happen already lives on the phone. Failure matters less here than for a capture — a
 * reading that did not start costs a tap, not a thought — but it is still reported, because the
 * alternative is a watch that looks like it did something and a phone that stayed silent.
 */
class ReadRequestSender(private val context: Context) {

    /** Sends the request, returning whether the phone acknowledged it. */
    suspend fun send(noteId: String): Boolean = withContext(Dispatchers.IO) {
        val payload = DataMap().apply {
            putString(ReadRequestKeys.KEY_NOTE_ID, noteId)
        }.toByteArray()

        try {
            val nodes = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes, TIMEOUT_SECONDS, TimeUnit.SECONDS
            )
            if (nodes.isEmpty()) {
                Log.w(TAG, "No connected node; nothing to read from")
                return@withContext false
            }

            for (node in nodes) {
                Tasks.await(
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, ReadRequestKeys.PATH, payload),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS
                )
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not ask the phone to read $noteId", e)
            false
        }
    }

    private companion object {
        const val TAG = "QuillRead"
        const val TIMEOUT_SECONDS = 10L
    }
}
