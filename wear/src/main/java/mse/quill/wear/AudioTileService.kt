package mse.quill.wear

import android.app.Activity
import android.content.ComponentName
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textButton
import androidx.wear.protolayout.types.LayoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders

/**
 * Quill's ear and mouth, on one tile: record a note, or have one read to you.
 *
 * <p>This replaced two tiles that each did one of those. They were separate on the argument that a
 * tile is a glance with one thing on it, which is true of [DueTileService] — a number is a claim
 * about the world and the tile exists to show it — and was not true of these. Neither had anything
 * to show. Both were a word and a door, and two doors side by side is what a tile's button group is
 * for; two tiles was two swipes to reach the second one.
 *
 * <p>Nothing here reads the Data Layer, so unlike the due tile there is nothing that can be stale.
 * Both buttons open a screen and that screen does the asking.
 */
class AudioTileService : Material3TileService() {

    override suspend fun MaterialScope.tileResponse(
        requestParams: RequestBuilders.TileRequest,
    ): TileBuilders.Tile {
        // `this` inside here is the MaterialScope, which carries a Context of its own — an
        // unqualified getString would silently pick the wrong one. See DueTileService.
        val service = this@AudioTileService

        val layout: LayoutElement = primaryLayout(
            titleSlot = { text(LayoutString(service.getString(R.string.tile_audio_headline))) },
            mainSlot = {
                // Stacked, not side by side. A button group splits the tile down the middle and
                // gives each half about a third of the screen's width, which is a column narrow
                // enough that "Record" fills it — a shape that says nothing about what is on it.
                // Full-width rows have room for the whole phrase and are the easier target.
                //
                // The column wraps its content rather than expanding: with fixed-height buttons
                // inside, an expanding column would only push them apart again.
                LayoutElementBuilders.Column.Builder()
                    .setWidth(expand())
                    .addContent(
                        actionButton(
                            service.open(CLICK_RECORD, CaptureActivity::class.java),
                            service.getString(R.string.tile_audio_record),
                        )
                    )
                    .addContent(
                        LayoutElementBuilders.Spacer.Builder()
                            .setHeight(dp(BUTTON_GAP_DP))
                            .build()
                    )
                    .addContent(
                        actionButton(
                            service.open(CLICK_READ, ReadAloudActivity::class.java),
                            service.getString(R.string.tile_audio_read),
                        )
                    )
                    .build()
            },
        )

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    /**
     * One full-width row of the stack: a label and the screen it opens.
     *
     * <p>A fixed height, and emphatically not `weight(1f)`. Weighted, the pair divided the whole
     * main slot between them — on a 454px watch that is two buttons around 90dp tall, each one a
     * third of the screen, which is what "way too big" meant. A tile's job is to be glanced at and
     * tapped once; the buttons only have to be reachable, and the empty band left under them is
     * quieter than two slabs.
     */
    private fun MaterialScope.actionButton(onClick: ModifiersBuilders.Clickable, label: String) =
        textButton(
            onClick = onClick,
            width = expand(),
            height = dp(BUTTON_HEIGHT_DP),
            labelContent = {
                // A step down from the button default too — at the default size "Record a note"
                // fills the width edge to edge and reads as a headline rather than a control.
                text(LayoutString(label), typography = Typography.LABEL_MEDIUM)
            },
        )

    /**
     * A click that opens one of this app's screens.
     *
     * <p>Unlike the due tile, the whole tile is not also a target: with two buttons on it there is
     * no single thing tapping the background could mean, and picking one of them for it would make
     * half the tile a mystery.
     */
    private fun open(id: String, destination: Class<out Activity>) =
        ModifiersBuilders.Clickable.Builder()
            .setId(id)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            // Explicit rather than implicit: the tile renderer starts this on the
                            // app's behalf, and an unresolved implicit intent fails silently.
                            .setPackageName(ComponentName(this, destination).packageName)
                            .setClassName(destination.name)
                            .build()
                    )
                    .build()
            )
            .build()

    private companion object {
        const val RESOURCES_VERSION = "1"

        /** Wear's floor for something you press, and no taller — see [actionButton]. */
        const val BUTTON_HEIGHT_DP = 48f

        /** Enough to read as two rows rather than one split pill, and no more. */
        const val BUTTON_GAP_DP = 4f

        /** Required by ProtoLayout — every Clickable needs an id. */
        const val CLICK_RECORD = "record"
        const val CLICK_READ = "read"
    }
}
