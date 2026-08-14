package mse.quill.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
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
 * <p>The same shape as [DueProjectionClient] and for the same reasons: the `DataItem` is the store,
 * there is no cache beside it, and `null` means "never synced" rather than "no notes". A picker
 * that showed an empty list to a watch that has simply never heard from its phone would be telling
 * the user their notes are gone.
 */
class NoteListClient(private val context: Context) {

    /** Reads the list, or `null` if the phone has never published one to this watch. */
    suspend fun read(): List<WatchNote>? = withContext(Dispatchers.IO) {
        val uri = Uri.Builder()
            .scheme(PutDataRequest.WEAR_URI_SCHEME)
            .path(NoteListKeys.PATH)
            .build()

        val buffer = try {
            Tasks.await(Wearable.getDataClient(context).getDataItems(uri), 10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return@withContext null
        }

        try {
            val item = buffer.firstOrNull() ?: return@withContext null
            val map = DataMapItem.fromDataItem(item).dataMap
            val ids = map.getStringArray(NoteListKeys.KEY_NOTE_IDS) ?: emptyArray()
            val titles = map.getStringArray(NoteListKeys.KEY_NOTE_TITLES) ?: emptyArray()

            // The shorter of the two, for the reason the projection takes the shortest of four:
            // degrading to fewer notes beats an IndexOutOfBounds inside a picker.
            val count = minOf(ids.size, titles.size)
            (0 until count).map { WatchNote(ids[it], titles[it]) }
        } finally {
            // A DataItemBuffer holds a native cursor; leaking it leaks that.
            buffer.release()
        }
    }
}

/** One note as the watch sees it: something to point at, and nothing else. */
data class WatchNote(val id: String, val title: String)
