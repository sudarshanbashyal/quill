package mse.quill.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mse.quill.data.ReadStateKeys
import java.util.concurrent.TimeUnit

/**
 * What the phone's voice is doing, as the watch last heard it.
 *
 * <p>The same shape as [NoteListClient] — the `DataItem` is the store and there is no cache beside
 * it — with one addition the pickers do not need: [observe]. A picker only has to be right when it
 * opens, whereas transport controls have to be right while they are being looked at. A reading that
 * ends on its own, or is paused from the phone, has to reach the buttons without the watch asking.
 */
class ReadStateClient(private val context: Context) {

    /** The last state published, or `null` if the phone has never read anything on this pairing. */
    suspend fun read(): ReadState? = withContext(Dispatchers.IO) {
        val uri = Uri.Builder()
            .scheme(PutDataRequest.WEAR_URI_SCHEME)
            .path(ReadStateKeys.PATH)
            .build()

        val buffer = try {
            Tasks.await(Wearable.getDataClient(context).getDataItems(uri), 10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return@withContext null
        }

        try {
            val item = buffer.firstOrNull() ?: return@withContext null
            ReadState.from(DataMapItem.fromDataItem(item))
        } finally {
            // A DataItemBuffer holds a native cursor; leaking it leaks that.
            buffer.release()
        }
    }

    /**
     * Calls [onState] whenever the phone publishes a new one, until the returned handle is closed.
     *
     * <p>Registered against the screen rather than a service: the only thing that reacts to this is
     * a set of buttons someone is looking at, and a listener that outlived them would be waking the
     * watch to update nothing.
     */
    fun observe(onState: (ReadState) -> Unit): Registration {
        val client = Wearable.getDataClient(context)
        val listener = DataClient.OnDataChangedListener { events ->
            try {
                for (event in events) {
                    if (event.type != DataEvent.TYPE_CHANGED) continue
                    if (event.dataItem.uri.path != ReadStateKeys.PATH) continue
                    onState(ReadState.from(DataMapItem.fromDataItem(event.dataItem)))
                }
            } finally {
                events.release()
            }
        }
        // Unfiltered, with the path checked in the callback instead. The URI-filtered overload
        // wants a host as well as a path, and the host here is the phone's node id — a round trip
        // to learn, and wrong the moment a second phone is paired. What it would save is being
        // woken by the due projection and the note list, which cost a string comparison.
        client.addListener(listener)
        return Registration { client.removeListener(listener) }
    }

    /** Undoes an [observe]. */
    fun interface Registration {
        fun close()
    }
}

/** The phone's reading, as much of it as the watch needs to draw two buttons. */
data class ReadState(
    val active: Boolean,
    val playing: Boolean,
    val title: String,
    val progress: Float,
) {
    companion object {
        fun from(item: DataMapItem): ReadState {
            val map = item.dataMap
            return ReadState(
                active = map.getBoolean(ReadStateKeys.KEY_ACTIVE, false),
                playing = map.getBoolean(ReadStateKeys.KEY_PLAYING, false),
                title = map.getString(ReadStateKeys.KEY_TITLE) ?: "",
                progress = map.getFloat(ReadStateKeys.KEY_PROGRESS, 0f),
            )
        }
    }
}
