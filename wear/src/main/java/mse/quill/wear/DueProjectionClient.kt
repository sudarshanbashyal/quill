package mse.quill.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mse.quill.data.DueProjectionKeys
import mse.quill.data.model.DueCard
import java.util.concurrent.TimeUnit

/**
 * What the phone last told this watch about today's cards.
 *
 * <p>There is no local database and no cache file here, and that is the design rather than a
 * shortcut: the `DataItem` the phone published *is* the store. The Data Layer already persists it
 * across reboots, replaces it atomically, and hands back the same bytes to every reader — a
 * SharedPreferences copy beside it would only add a second thing that can be stale.
 */
class DueProjectionClient(private val context: Context) {

    /**
     * Reads the projection, or `null` if the phone has never published one to this watch.
     *
     * <p>`null` and "zero cards" are deliberately different answers. Nothing published means the
     * pair has never synced and the honest surface is "open Quill on your phone"; an empty card
     * list means the phone looked and there is genuinely nothing due, which is "all caught up".
     * Collapsing the two would have the tile congratulate someone whose watch simply isn't paired.
     */
    suspend fun read(): DueSnapshot? = withContext(Dispatchers.IO) {
        val uri = Uri.Builder()
            .scheme(PutDataRequest.WEAR_URI_SCHEME)
            .path(DueProjectionKeys.PATH)
            .build()

        // Tasks.await rather than kotlinx-coroutines-play-services: one blocking call on
        // Dispatchers.IO costs less than a dependency whose only use would be this line.
        val buffer = try {
            Tasks.await(Wearable.getDataClient(context).getDataItems(uri), 10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // A watch that can't reach its own Data Layer is a watch with nothing to show. Failing
            // soft here is what keeps the tile on "open Quill on your phone" instead of blank.
            return@withContext null
        }

        try {
            val item = buffer.firstOrNull() ?: return@withContext null
            decode(DataMapItem.fromDataItem(item))
        } finally {
            // A DataItemBuffer holds a native cursor; leaking it leaks that.
            buffer.release()
        }
    }

    private fun decode(item: DataMapItem): DueSnapshot {
        val map = item.dataMap
        val ids = map.getStringArray(DueProjectionKeys.KEY_CARD_IDS) ?: emptyArray()
        val fronts = map.getStringArray(DueProjectionKeys.KEY_CARD_FRONTS) ?: emptyArray()
        val backs = map.getStringArray(DueProjectionKeys.KEY_CARD_BACKS) ?: emptyArray()
        val dueAt = map.getLongArray(DueProjectionKeys.KEY_CARD_DUE_AT) ?: longArrayOf()

        // Four parallel arrays are four chances to disagree. Taking the shortest is the version
        // that degrades to "fewer cards" rather than to an IndexOutOfBounds inside a TileService,
        // where the failure would surface as a tile that silently refuses to load.
        val count = minOf(ids.size, fronts.size, backs.size, dueAt.size)

        val cards = ArrayList<DueCard>(count)
        for (i in 0 until count) {
            cards += DueCard().apply {
                id = ids[i]
                front = fronts[i]
                back = backs[i]
                this.dueAt = dueAt[i]
            }
        }

        return DueSnapshot(
            generatedAt = map.getLong(DueProjectionKeys.KEY_GENERATED_AT),
            horizon = map.getLong(DueProjectionKeys.KEY_HORIZON),
            cards = cards,
        )
    }
}

/** One published projection, as received. */
data class DueSnapshot(
    val generatedAt: Long,
    val horizon: Long,
    val cards: List<DueCard>,
) {
    /**
     * The cards actually due *now*, by the watch's own clock.
     *
     * <p>This is the other half of the end-of-day horizon: the phone ships everything due before
     * midnight so a card coming due at 17:00 is already on the wrist, and the count only starts
     * including it once 17:00 arrives. Without this re-filter the tile would count tonight's cards
     * all morning.
     */
    fun dueAt(now: Long): List<DueCard> = cards.filter { it.dueAt <= now }
}
