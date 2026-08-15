package mse.quill.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mse.quill.data.NoteListKeys
import java.util.concurrent.TimeUnit

/**
 * The notes the phone last offered this watch — what both pickers are built from.
 *
 * <p>The same shape as [DueProjectionClient]: the `DataItem` is the store, there is no cache beside
 * it, and `null` means "never synced" rather than "no notes". A picker that showed an empty list to
 * a watch that has simply never heard from its phone would be telling the user their notes are
 * gone.
 *
 * <p><b>Read, then ask.</b> The phone republishes on every change, so what is already here is
 * almost always right — which is why the picker draws it immediately rather than waiting. But
 * "almost always" is not a property a picker can rely on: a missed publish leaves a note on the
 * wrist that no longer exists, and choosing it files a memo somewhere the user did not pick. So
 * [requestRefresh] asks for a rebuild on the way in and [observe] applies whatever comes back.
 */
class NoteListClient(private val context: Context) {

    /** The list as last published, or `null` if the phone has never published one to this watch. */
    suspend fun read(): WatchNoteList? = withContext(Dispatchers.IO) {
        val buffer = try {
            Tasks.await(Wearable.getDataClient(context).getDataItems(listUri()), 10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return@withContext null
        }

        try {
            val item = buffer.firstOrNull() ?: return@withContext null
            WatchNoteList.from(DataMapItem.fromDataItem(item))
        } finally {
            // A DataItemBuffer holds a native cursor; leaking it leaks that.
            buffer.release()
        }
    }

    /**
     * Asks the phone to rebuild the list. Returns whether the ask was delivered, not whether
     * anything changed — the answer arrives separately, through [observe].
     */
    suspend fun requestRefresh(): Boolean = withContext(Dispatchers.IO) {
        try {
            val nodes = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes, TIMEOUT_SECONDS, TimeUnit.SECONDS
            )
            if (nodes.isEmpty()) return@withContext false
            for (node in nodes) {
                Tasks.await(
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, NoteListKeys.REFRESH_PATH, ByteArray(0)),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS,
                )
            }
            true
        } catch (e: Exception) {
            // A watch with no phone in range. The picker keeps what it has, which is the best
            // answer available and is usually the right one.
            Log.w(TAG, "Could not ask the phone to refresh the note list", e)
            false
        }
    }

    /** Calls [onList] whenever the phone publishes a new list, until the handle is closed. */
    fun observe(onList: (WatchNoteList) -> Unit): Registration {
        val client = Wearable.getDataClient(context)
        val listener = DataClient.OnDataChangedListener { events ->
            try {
                for (event in events) {
                    if (event.type != DataEvent.TYPE_CHANGED) continue
                    if (event.dataItem.uri.path != NoteListKeys.PATH) continue
                    onList(WatchNoteList.from(DataMapItem.fromDataItem(event.dataItem)))
                }
            } finally {
                events.release()
            }
        }
        // Unfiltered, with the path checked above — the URI-filtered overload wants a host as well,
        // and the host is the phone's node id. See ReadStateClient for the same trade.
        client.addListener(listener)
        return Registration { client.removeListener(listener) }
    }

    private fun listUri(): Uri = Uri.Builder()
        .scheme(PutDataRequest.WEAR_URI_SCHEME)
        .path(NoteListKeys.PATH)
        .build()

    /** Undoes an [observe]. */
    fun interface Registration {
        fun close()
    }

    private companion object {
        const val TAG = "QuillNoteList"
        const val TIMEOUT_SECONDS = 10L
    }
}

/**
 * One published list, and when the phone built it.
 *
 * <p>[generatedAt] is what lets a screen tell a genuinely newer list from the same one arriving
 * again — the Data Layer redelivers on reconnect, and swapping the rows under a thumb for identical
 * rows is a way to lose a tap.
 */
data class WatchNoteList(val generatedAt: Long, val notes: List<WatchNote>) {
    companion object {
        fun from(item: DataMapItem): WatchNoteList {
            val map = item.dataMap
            val ids = map.getStringArray(NoteListKeys.KEY_NOTE_IDS) ?: emptyArray()
            val titles = map.getStringArray(NoteListKeys.KEY_NOTE_TITLES) ?: emptyArray()

            // The shorter of the two, for the reason the projection takes the shortest of four:
            // degrading to fewer notes beats an IndexOutOfBounds inside a picker.
            val count = minOf(ids.size, titles.size)
            return WatchNoteList(
                generatedAt = map.getLong(NoteListKeys.KEY_GENERATED_AT),
                notes = (0 until count).map { WatchNote(ids[it], titles[it]) },
            )
        }
    }
}

/** One note as the watch sees it: something to point at, and nothing else. */
data class WatchNote(val id: String, val title: String)
