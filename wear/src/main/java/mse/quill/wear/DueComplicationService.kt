package mse.quill.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * The due count on the watch face, which is the cheapest surface Quill has and the one most likely
 * to actually be seen.
 *
 * <p>Short text only. A ranged value would want a maximum, and "cards due" has no natural ceiling —
 * a progress arc against an invented denominator would be a chart of nothing.
 */
class DueComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type != ComplicationType.SHORT_TEXT) null
        else shortText("7")

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null

        // No data rather than a zero when the phone has never published: a watch face reading "0"
        // would tell the user they are up to date on the strength of never having heard from it.
        val snapshot = DueProjectionClient(this).read() ?: return null

        return shortText(snapshot.dueAt(System.currentTimeMillis()).size.toString())
    }

    private fun shortText(value: String): ShortTextComplicationData =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(value).build(),
            contentDescription = PlainComplicationText.Builder(
                getString(R.string.complication_label)
            ).build(),
        ).build()
}
