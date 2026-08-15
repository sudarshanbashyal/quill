package mse.quill.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonColors
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pick a note, have the phone read it out — and stop it again from here.
 *
 * <p>The phone still does everything that makes a reading happen: loading the text, flattening it,
 * driving the {@code TextToSpeech} engine. What changed is that the watch no longer walks away
 * afterwards. Starting a reading from the wrist and then having to unlock the phone and find the
 * now-playing bar to pause it made the feature something you would think twice about using, which
 * is a strange property for a one-tap convenience.
 *
 * <p>So this screen has two lives. Opened while nothing is being read, it is a picker. Opened while
 * the phone is mid-note — however that reading was started, including from the phone itself — it
 * goes straight to the controls, because someone reaching for Quill on their watch during a reading
 * is reaching for the pause button.
 *
 * <p>The buttons act optimistically and are corrected by [ReadStateClient] a moment later. A
 * round trip over Bluetooth is a visible pause on a control that should feel instant, and the state
 * item is the authority on what actually happened either way.
 */
class ReadAloudActivity : ComponentActivity() {

    private var phase by mutableStateOf(Phase.LOADING)

    /** What the controls draw. Locally optimistic between a tap and the phone's next publish. */
    private var state by mutableStateOf(ReadState(false, false, "", 0f))

    /**
     * True between asking for a reading and hearing that one started.
     *
     * <p>Without it the screen closes itself immediately: the state item still holds the *previous*
     * reading's ending — `active = false` — and arrives long before the new reading's beginning.
     */
    private var awaitingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ReadScreen() } }
    }

    @Composable
    private fun ReadScreen() {
        val client = ReadStateClient(this)
        // Started regardless of which screen we land on: the round trip runs while the read state
        // is being fetched, so by the time the picker is reached the answer is usually already in.
        val list = rememberSyncedNoteList(this)
        val notes = list.notes

        LaunchedEffect(Unit) {
            val current = client.read()
            if (current != null && current.active) {
                // Already reading. The picker would be the wrong screen to open on — see the class
                // note — and choosing from it would silently replace what is playing.
                state = current
                phase = Phase.CONTROLLING
            } else {
                phase = Phase.CHOOSING
            }
        }

        DisposableEffect(Unit) {
            val registration = client.observe { published -> onStateReceived(published) }
            onDispose { registration.close() }
        }

        when (phase) {
            Phase.LOADING -> Centered { CircularProgressIndicator() }

            Phase.CHOOSING -> when {
                // The list read has its own moment, separate from the read state's.
                !list.loaded -> Centered { CircularProgressIndicator() }

                // Never synced is not the same as having no notes — see NoteListClient.
                notes == null -> Message(getString(R.string.read_no_phone))

                notes.isEmpty() -> Message(getString(R.string.read_no_notes))

                else -> PickerList(
                    items = notes,
                    label = { it.title },
                    header = getString(R.string.read_pick_note),
                    syncing = list.syncing,
                ) { picked ->
                    startReading(picked)
                }
            }

            Phase.CONTROLLING -> Controls()
            Phase.FAILED -> Message(getString(R.string.read_failed))
            Phase.FINISHED -> Message(getString(R.string.read_finished))
        }

        if (phase == Phase.FAILED || phase == Phase.FINISHED) {
            LaunchedEffect(phase) {
                delay(if (phase == Phase.FINISHED) FINISHED_LINGER_MS else FAILED_LINGER_MS)
                finish()
            }
        }
    }

    /**
     * The transport: what is being read, how far in, and the two things you can do about it.
     *
     * <p>Round icon buttons side by side, the same shape as the review screen's tick and cross —
     * two glyphs fit where two words had to stack, which leaves the room the title needed. Stop
     * carries the error colours rather than the primary ones: it is the only control here that
     * cannot be undone, and it sits a thumb's width from the one you press repeatedly.
     *
     * <p>The glyphs are this module's own vectors. `material-icons-core` has a play triangle and
     * neither of the other two, and pulling the extended set onto a watch to draw a square and two
     * bars is not a trade worth making.
     */
    @Composable
    private fun Controls() {
        // The reading's position, as a ring around everything else — the one thing on this screen
        // that a watch's shape is actually good at showing.
        CircularProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxSize().padding(2.dp),
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = state.title.ifEmpty { getString(R.string.read_untitled) },
                textAlign = TextAlign.Center,
                maxLines = 2,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = getString(
                    if (state.playing) R.string.read_playing else R.string.read_paused
                ),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp),
            )

            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                TransportButton(
                    icon = if (state.playing) R.drawable.ic_pause else R.drawable.ic_play,
                    // The label the button no longer shows is the only thing a screen reader has
                    // left to announce.
                    label = getString(
                        if (state.playing) R.string.read_pause else R.string.read_resume
                    ),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                    onClick = { toggle() },
                )
                TransportButton(
                    icon = R.drawable.ic_stop,
                    label = getString(R.string.read_stop),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    onClick = { stopReading() },
                )
            }
        }
    }

    @Composable
    private fun TransportButton(
        icon: Int,
        label: String,
        colors: IconButtonColors,
        onClick: () -> Unit,
    ) {
        FilledIconButton(
            onClick = onClick,
            colors = colors,
            modifier = Modifier.size(CaptureActivity.TRANSPORT_BUTTON_DP.dp),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                modifier = Modifier.size(CaptureActivity.TRANSPORT_ICON_DP.dp),
            )
        }
    }

    private fun startReading(picked: WatchNote) {
        // Straight to the controls, on the assumption it will work — the alternative is a
        // "Starting…" screen that exists only to cover a round trip.
        state = ReadState(active = true, playing = true, title = picked.title, progress = 0f)
        awaitingStart = true
        phase = Phase.CONTROLLING

        CoroutineScope(Dispatchers.Main).launch {
            if (!ReadRequestSender(this@ReadAloudActivity).send(picked.id)) {
                awaitingStart = false
                phase = Phase.FAILED
                return@launch
            }
            // The phone accepted the request; whether a voice actually started is a separate
            // question — an empty note, or one whose collection was locked since the list was
            // published, leaves nothing to say and publishes no reading.
            delay(START_GRACE_MS)
            if (awaitingStart) {
                awaitingStart = false
                val current = ReadStateClient(this@ReadAloudActivity).read()
                if (current != null && current.active) {
                    state = current
                } else {
                    // Nothing came of it, and it is over either way — a note short enough to
                    // finish inside the grace period lands here too, which is why this closes
                    // quietly rather than reporting a failure.
                    phase = Phase.FINISHED
                }
            }
        }
    }

    private fun toggle() {
        val wasPlaying = state.playing
        state = state.copy(playing = !wasPlaying)
        CoroutineScope(Dispatchers.Main).launch {
            // Not corrected on failure: the phone's next publish is the correction, and one that
            // never arrives means a reading the watch cannot see and cannot help with.
            ReadControlSender(this@ReadAloudActivity).toggle()
        }
    }

    private fun stopReading() {
        awaitingStart = false
        phase = Phase.FINISHED
        CoroutineScope(Dispatchers.Main).launch {
            ReadControlSender(this@ReadAloudActivity).stop()
        }
    }

    /** The phone's word on what is happening, which outranks whatever this screen assumed. */
    private fun onStateReceived(published: ReadState) {
        if (published.active) {
            awaitingStart = false
            state = published
            if (phase == Phase.CHOOSING || phase == Phase.LOADING) phase = Phase.CONTROLLING
            return
        }

        // A reading that has ended. Ignored while waiting for ours to start: this is the previous
        // one's ending arriving late, not an answer about the reading just asked for.
        if (awaitingStart) return
        if (phase == Phase.CONTROLLING) phase = Phase.FINISHED
    }

    @Composable
    private fun Message(text: String) {
        Centered {
            Text(
                text = text,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    @Composable
    private fun Centered(content: @Composable () -> Unit) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }

    private enum class Phase { LOADING, CHOOSING, CONTROLLING, FINISHED, FAILED }

    private companion object {
        /** How long a requested reading has to announce itself before the screen goes looking. */
        const val START_GRACE_MS = 5000L

        /** Shorter than the failure's: the voice stopping is its own confirmation. */
        const val FINISHED_LINGER_MS = 1200L
        const val FAILED_LINGER_MS = 3500L
    }
}
