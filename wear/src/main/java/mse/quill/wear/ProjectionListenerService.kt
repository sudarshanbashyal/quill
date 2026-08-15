package mse.quill.wear

import android.content.ComponentName
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import mse.quill.data.DueProjectionKeys

/**
 * Wakes when the phone publishes a new projection and asks the two surfaces to redraw.
 *
 * <p>It stores nothing. Both the tile and the complication read the `DataItem` themselves, so this
 * service's whole job is to say "it changed" — which is also why there is no ordering problem
 * between the write landing and the redraw reading it: by the time this callback runs, the item is
 * already in the Data Layer's store.
 */
class ProjectionListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        // The manifest filter already narrows to /quill/due, but a path check costs nothing and
        // keeps this correct if a second DataItem is ever added.
        val touched = events.any { it.dataItem.uri.path == DueProjectionKeys.PATH }
        if (!touched) return

        TileService.getUpdater(this).requestUpdate(DueTileService::class.java)

        ComplicationDataSourceUpdateRequester
            .create(this, ComponentName(this, DueComplicationService::class.java))
            .requestUpdateAll()
    }
}
