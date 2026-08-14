package mse.quill.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mse.quill.data.AnswerEventKeys
import java.util.concurrent.TimeUnit

/**
 * Sends one answer back to the phone.
 *
 * <p>A message and not a `DataItem`, because an answer is an event: the projection is current
 * state and is replaced wholesale, but two answers to the same card are two separate facts that
 * both have to land. Keying them by card id — which is what a `DataItem` would do — would lose the
 * first one.
 *
 * <p>Carries the watch's clock reading with it. The phone schedules from when the card was
 * answered rather than from when the message arrived, so the timestamp has to travel with the
 * event; see `AnswerEventKeys.KEY_ANSWERED_AT`.
 */
class AnswerSender(private val context: Context) {

    /**
     * Sends the answer, returning whether the phone acknowledged it.
     *
     * <p>Failing soft is deliberate and the review screen ignores the result: a dropped answer
     * costs one card's schedule advance, and stopping a session to report it would cost the
     * session. What it does *not* do is retry or queue — see the note on the class.
     */
    suspend fun send(cardId: String, correct: Boolean, answeredAt: Long): Boolean =
        withContext(Dispatchers.IO) {
            val payload = DataMap().apply {
                putString(AnswerEventKeys.KEY_CARD_ID, cardId)
                putBoolean(AnswerEventKeys.KEY_CORRECT, correct)
                putLong(AnswerEventKeys.KEY_ANSWERED_AT, answeredAt)
            }.toByteArray()

            try {
                // The paired phone is the only connected node in a tethered pair, but sending to
                // every one is still the right shape: the alternative is picking one by index and
                // being wrong the first time a second node appears.
                val nodes = Tasks.await(
                    Wearable.getNodeClient(context).connectedNodes, 10, TimeUnit.SECONDS
                )
                if (nodes.isEmpty()) {
                    Log.w(TAG, "No connected node; dropping the answer for $cardId")
                    return@withContext false
                }

                var delivered = false
                for (node in nodes) {
                    Tasks.await(
                        Wearable.getMessageClient(context)
                            .sendMessage(node.id, AnswerEventKeys.PATH, payload),
                        10, TimeUnit.SECONDS
                    )
                    delivered = true
                }
                delivered
            } catch (e: Exception) {
                Log.w(TAG, "Could not send the answer for $cardId", e)
                false
            }
        }

    private companion object {
        const val TAG = "QuillAnswer"
    }
}
