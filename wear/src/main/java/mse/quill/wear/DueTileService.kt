package mse.quill.wear

import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.types.LayoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders

/**
 * The tile: how many Quill cards are due, and nothing else.
 *
 * <p>Extends {@code Material3TileService} rather than the plain {@code TileService} — that is the
 * Kotlin-only entry point, and the reason this module is Kotlin at all. It hands the layout a
 * {@code MaterialScope} already carrying the system's Material 3 Expressive theme, so the tile
 * picks up the watch face's colours instead of hard-coding Quill's.
 *
 * <p>Nothing here polls. The tile is redrawn when {@link ProjectionListenerService} says the phone
 * published something new, which is the only moment the number can have changed.
 */
class DueTileService : Material3TileService() {

    // The MaterialScope arrives as the receiver, not a parameter — so `primaryLayout` and `text`
    // below are in scope directly and carry the system theme without being passed around.
    override suspend fun MaterialScope.tileResponse(
        requestParams: RequestBuilders.TileRequest,
    ): TileBuilders.Tile {
        // `this` is the MaterialScope inside this function, so the service has to be named — the
        // scope has a Context of its own and an unqualified `this` picks the wrong one.
        val service = this@DueTileService
        val snapshot = DueProjectionClient(service).read()
        val dueNow = snapshot?.dueAt(System.currentTimeMillis())

        val headline = when {
            // Never synced. Not the same as "nothing due" — see DueProjectionClient.read.
            dueNow == null -> service.getString(R.string.tile_no_phone)
            dueNow.isEmpty() -> service.getString(R.string.tile_caught_up)
            else -> service.resources.getQuantityString(
                R.plurals.tile_cards_due, dueNow.size, dueNow.size
            )
        }

        val layout: LayoutElement = primaryLayout(
            titleSlot = { text(LayoutString(service.getString(R.string.app_name))) },
            mainSlot = { text(LayoutString(headline)) },
        )

        return TileBuilders.Tile.Builder()
            // No resources of our own — the layout is text on the system theme — but the field is
            // required, and a constant is honest here rather than lazy: it only has to change when
            // the resources do, and there aren't any.
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
    }
}
