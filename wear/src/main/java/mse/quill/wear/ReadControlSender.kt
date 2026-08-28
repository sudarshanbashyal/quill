package mse.quill.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mse.quill.sync.ReadControlKeys
import java.util.concurrent.TimeUnit

/**
 * Pauses, resumes or stops the reading on the phone.
 *
 * <p>A message, like [ReadRequestSender] and for the same reason: a transport command means nothing
 * to a phone that is not connected, because a phone that is not connected is not reading anything
 * of ours. There is nothing here worth queueing for later — a pause that arrives in an hour is a
 * pause nobody wanted.
 */
class ReadControlSender(private val context: Context) {

    /** Pause if speaking, resume if paused. See [ReadControlKeys.ACTION_TOGGLE] for why a toggle. */
    suspend fun toggle(): Boolean = send(ReadControlKeys.ACTION_TOGGLE)

    /** End the reading. */
    suspend fun stop(): Boolean = send(ReadControlKeys.ACTION_STOP)

    private suspend fun send(action: String): Boolean = withContext(Dispatchers.IO) {
        val payload = DataMap().apply {
            putString(ReadControlKeys.KEY_ACTION, action)
        }.toByteArray()

        try {
            val nodes = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes, TIMEOUT_SECONDS, TimeUnit.SECONDS
            )
            if (nodes.isEmpty()) {
                Log.w(TAG, "No connected node; nothing to control")
                return@withContext false
            }
            for (node in nodes) {
                Tasks.await(
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, ReadControlKeys.PATH, payload),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS
                )
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not send the read control", e)
            false
        }
    }

    private companion object {
        const val TAG = "QuillRead"
        const val TIMEOUT_SECONDS = 10L
    }
}
