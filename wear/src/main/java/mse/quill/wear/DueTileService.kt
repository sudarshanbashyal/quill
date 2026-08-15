package mse.quill.wear

import android.content.ComponentName
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
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

        // A count is three characters and wants the display face; a sentence at that size is two
        // words and an ellipsis. So the type follows the message rather than the slot — which is
        // what the old twenty-character ceiling was really describing.
        val headlineIsSentence = dueNow.isNullOrEmpty()

        // One Clickable, used twice: by the edge button below and by the whole-tile wrapper.
        // Built once rather than twice so the two targets cannot drift apart.
        val openReview = ModifiersBuilders.Clickable.Builder()
            .setId(CLICK_REVIEW)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            // Named explicitly rather than via an implicit intent: the tile
                            // renderer starts this on the app's behalf and an unresolved implicit
                            // intent fails silently there.
                            .setPackageName(
                                ComponentName(service, ReviewActivity::class.java).packageName
                            )
                            .setClassName(ReviewActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()

        // The edge button only when there is a session to open. "Review" under "All caught up" is
        // a button that leads to a screen saying the same thing, and under "Open on phone" it
        // would offer a session the watch has no cards for.
        val hasSession = !dueNow.isNullOrEmpty()

        val layout: LayoutElement = primaryLayout(
            titleSlot = { text(LayoutString(service.getString(R.string.app_name))) },
            mainSlot = {
                if (headlineIsSentence) {
                    text(
                        LayoutString(headline),
                        typography = Typography.BODY_MEDIUM,
                        maxLines = SENTENCE_MAX_LINES,
                    )
                } else {
                    text(LayoutString(headline))
                }
            },
            bottomSlot = if (!hasSession) null else {
                {
                    textEdgeButton(onClick = openReview) {
                        // Just "Review": the count is already in the main slot directly above,
                        // and an edge button is a narrow arc — "Review 12" under "12 cards due"
                        // spends that width saying the number twice.
                        text(LayoutString(service.getString(R.string.tile_review_action)))
                    }
                }
            },
        )

        // The whole tile stays a target as well as the button. A tile is a glance with one thing
        // on it, and requiring a hit on the arc at the bottom is a smaller target for the same
        // intent — the edge button labels the action, it does not have to be the only way in.
        // Wrapping is also why this survives the layout changing: the click lives on the
        // container, not on the text.
        val clickable: LayoutElement = LayoutElementBuilders.Box.Builder()
            .addContent(layout)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(openReview)
                    .build()
            )
            .build()

        return TileBuilders.Tile.Builder()
            // No resources of our own — the layout is text on the system theme — but the field is
            // required, and a constant is honest here rather than lazy: it only has to change when
            // the resources do, and there aren't any.
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(clickable))
            .build()
    }

    private companion object {
        const val RESOURCES_VERSION = "1"

        /** Room for a sentence without the tile becoming something to read rather than glance at. */
        const val SENTENCE_MAX_LINES = 4

        /** Required by ProtoLayout — every Clickable needs an id, even one with a single target. */
        const val CLICK_REVIEW = "review"
    }
}
