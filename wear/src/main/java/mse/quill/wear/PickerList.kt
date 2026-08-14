package mse.quill.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/**
 * A scrollable list of things to tap, with the watch behaviours every such list needs.
 *
 * <p>Extracted once the third picker appeared — decks, notes to dictate into, notes to be read.
 * The part worth sharing is not the column but the things easy to leave out of one: rotary, so the
 * crown and the bezel work; vertical padding, so the first and last rows are not sitting where a
 * round screen curves away; and a row that is a <em>surface</em> rather than a line of text.
 *
 * <p>That last one was a real bug rather than a preference. The rows used to be bare white text on
 * black, and on a list of three notes with two-line titles there was nothing to say where one ended
 * and the next began — the eye had only the gaps to go on, and the gap inside a wrapped title is
 * the same gap.
 *
 * <p><b>On sizing.</b> Everything here that can be tightened has been: type a step down, content
 * padding at 2dp, 4dp between rows, and much less padding above the first row than a bare-text list
 * needed. What has <em>not</em> moved is the row height, which sits on Wear's own 52dp button
 * minimum — a list on a wrist is operated by a fingertip belonging to someone who is walking, and
 * the floor below this one is 48dp, at which point titles have to give up their second line.
 */
@Composable
fun <T> PickerList(
    items: List<T>,
    label: (T) -> String,
    subtitle: (T) -> String? = { null },
    /** A line above the rows saying what picking one will do. Scrolls with them. */
    header: String? = null,
    /** Rows that answer false are shown and greyed rather than hidden — see the deck picker. */
    enabled: (T) -> Boolean = { true },
    /**
     * An action above the rows — something that is not one of [items] at all.
     *
     * <p>A slot rather than another kind of item, because the difference is the point: everything
     * in the list is a place to put something, and whatever goes here makes a new one. Callers
     * style it to say so; see the capture screen's "New note".
     */
    leadingContent: (@Composable () -> Unit)? = null,
    onPick: (T) -> Unit,
) {
    val scroll = rememberScrollState()
    val rotaryFocus = remember { FocusRequester() }
    // Rotary is delivered to whatever holds focus, so it has to be taken rather than declared.
    LaunchedEffect(Unit) { rotaryFocus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .rotaryScrollable(
                behavior = RotaryScrollableDefaults.behavior(scrollableState = scroll),
                focusRequester = rotaryFocus,
            )
            .verticalScroll(scroll)
            // Wider side margins than the bare-text rows needed. Text can run to the curve and
            // still be read; a filled row cannot — its corners get sliced off by the bezel, which
            // reads as a rendering fault rather than as a round screen.
            .padding(horizontal = 16.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (header != null) {
            // Above the rows and inside the scroll, not pinned. A watch screen has no room for a
            // permanent header, and this one has nothing to say after the first row is read.
            Text(
                text = header,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    // Inset well beyond the rows below it. A row is a rounded rectangle whose
                    // corners the bezel is welcome to graze, but a line of text at the top of a
                    // circle has to fit inside a chord much shorter than the screen is wide —
                    // without this the first and last words are simply gone.
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 2.dp),
            )
        }

        leadingContent?.invoke()

        for (item in items) {
            val sub = subtitle(item)
            Button(
                onClick = { onPick(item) },
                enabled = enabled(item),
                // Tonal rather than filled: a list where every row is a primary-coloured button is
                // a list where nothing is emphasised, and these rows are all the same weight.
                colors = ButtonDefaults.filledTonalButtonColors(),
                contentPadding = ROW_PADDING,
                modifier = Modifier.fillMaxWidth(),
                secondaryLabel = if (sub == null) null else {
                    {
                        Text(
                            text = sub,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
            ) {
                Text(
                    text = label(item),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    // Two lines, then truncate: a note title can be a sentence, and a row that
                    // grows to five lines pushes every other choice off the screen.
                    maxLines = 2,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Shared by the rows and by anything a caller puts in [PickerList]'s leading slot, so an action
 *  above the list is the same height as the list. */
val ROW_PADDING: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
