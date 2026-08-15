package mse.quill.wear

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * The note list a picker draws, kept honest for as long as the picker is on screen.
 *
 * <p>Both pickers used to read the published list once and trust it. That was fine while the only
 * thing that changed a note was a save — which republished — and wrong as soon as anything else
 * did: a note deleted on the phone stayed on the wrist, offered and tappable, and a memo aimed at
 * it arrived at a phone that no longer had it.
 *
 * <p>The phone-side gaps are closed (a delete, a move and a collection lock all republish now), so
 * this is the belt to that pair of braces rather than the fix. It exists because no publish
 * mechanism can promise delivery, and because the watch cannot tell a current list from a stale one
 * by looking at it — both are the same {@code DataItem} with a different number inside.
 *
 * <p><b>Draw first, ask second.</b> The cached list appears immediately, because it is nearly
 * always right and a picker you watch load is a worse picker. The refresh runs behind it and the
 * rows are replaced only if they actually differ — swapping identical rows under a thumb is a way
 * to lose a tap, and the Data Layer redelivers on reconnect often enough for that to matter.
 */
class NoteListState {
    /** `null` means the phone has never published to this watch — not "no notes". */
    var notes by mutableStateOf<List<WatchNote>?>(null)
        internal set

    /** Whether the first read has finished, whatever it found. */
    var loaded by mutableStateOf(false)
        internal set

    /** Whether a rebuild has been asked for and not yet answered. Drives the picker's spinner. */
    var syncing by mutableStateOf(false)
        internal set
}

/**
 * Reads the cached list, asks the phone to rebuild it, and applies the answer when it lands.
 *
 * <p>The wait has a ceiling: a watch out of range never gets an answer, and a spinner that spins
 * forever is a worse lie than a list that might be a minute old.
 */
@Composable
fun rememberSyncedNoteList(context: Context): NoteListState {
    val state = remember { NoteListState() }
    val client = remember { NoteListClient(context) }
    var generatedAt by remember { mutableLongStateOf(0L) }

    // Registered before the read, so a list that lands between the two is not missed.
    DisposableEffect(Unit) {
        val registration = client.observe { published ->
            if (published.generatedAt < generatedAt) return@observe
            generatedAt = published.generatedAt
            // Only when they genuinely differ — see the class note on losing taps.
            if (published.notes != state.notes) state.notes = published.notes
            state.loaded = true
            state.syncing = false
        }
        onDispose { registration.close() }
    }

    LaunchedEffect(Unit) {
        val cached = client.read()
        if (cached != null) {
            generatedAt = cached.generatedAt
            state.notes = cached.notes
        }
        state.loaded = true

        state.syncing = true
        if (!client.requestRefresh()) {
            // No phone in range. Nothing is coming, so stop pretending.
            state.syncing = false
        } else {
            delay(SYNC_TIMEOUT_MS)
            state.syncing = false
        }
    }

    return state
}

/** Long enough for a round trip over Bluetooth, short enough not to be a wait. */
private const val SYNC_TIMEOUT_MS = 6000L
