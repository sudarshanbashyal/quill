package mse.quill.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Record a thought, into a note you choose.
 *
 * <p>The one authoring act a watch does better than a pocketed phone — and, since this screen was
 * rewritten, an actual recording rather than a transcript of one. It used to hand the job to the
 * system speech recogniser, which ends a capture the moment you stop making noise: a pause to find
 * the next word was read as being finished, and what reached the phone was a transcriber's guess at
 * what had been said. Now the microphone runs until Stop is tapped and the audio itself is what
 * lands in the note.
 *
 * <p>Nothing is kept here. The memo goes to the phone, which owns notes and is the only device that
 * can file this one; the watch's copy is deleted as soon as the Data Layer has taken it. What the
 * watch <em>can</em> do is queue — see [AudioCaptureSender] — so a memo recorded with the phone out
 * of range is stored, not lost, and the screen says so rather than claiming it arrived.
 *
 * <p>The inbox is offered first and needs no choosing: it is the destination for the case this
 * feature exists for, which is having a thought and not wanting to think about where it goes.
 */
class CaptureActivity : ComponentActivity() {

    private val recorder by lazy { MemoRecorder(this) }

    private var phase by mutableStateOf(Phase.CHOOSING)
    private var notes by mutableStateOf<List<WatchNote>?>(null)
    private var loaded by mutableStateOf(false)

    /** Redrawn from the recorder a few times a second while it runs — see [RecordingScreen]. */
    private var elapsedMs by mutableLongStateOf(0L)
    private var level by mutableFloatStateOf(0f)

    /** Set by the pick that starts the recording; the default is only ever a guard. */
    private var target: CaptureTarget = CaptureTarget.NewNote

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginRecording() else phase = Phase.DENIED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { CaptureScreen() } }
    }

    /**
     * A recording in progress when the screen goes away is thrown out rather than sent.
     *
     * <p>The alternative — filing whatever had been captured so far — sounds generous and is not:
     * the common way to leave this screen mid-memo is the back gesture, which is how a person says
     * they have changed their mind.
     */
    override fun onStop() {
        super.onStop()
        if (recorder.isRecording) {
            recorder.cancel()
            finish()
        }
    }

    @Composable
    private fun CaptureScreen() {
        LaunchedEffect(Unit) {
            notes = NoteListClient(this@CaptureActivity).read()
            loaded = true
        }

        when (phase) {
            Phase.CHOOSING -> {
                if (!loaded) {
                    Centered { CircularProgressIndicator() }
                } else {
                    // The inbox is a row like any other so the list has one shape, but it is
                    // pinned to the top and carries no note id: it is a destination, not a note
                    // the watch happens to know about.
                    // Make one, or add to one you have. There is no third option: the inbox used
                    // to sit at the top of this list as a destination of its own, which meant a
                    // watch that had already used it showed "Inbox" twice — once as the special
                    // row and once as the ordinary note it actually is. It still exists on the
                    // phone, as the place a memo lands when its chosen note has gone; it is no
                    // longer something to choose.
                    PickerList(
                        items = notes.orEmpty(),
                        label = { it.title },
                        // Says what the tap does. Without it the screen is a list of note names
                        // with no clue that recording starts the moment one is chosen.
                        header = getString(R.string.capture_pick_note),
                        leadingContent = {
                            NewNoteButton {
                                target = CaptureTarget.NewNote
                                requestMicThenRecord()
                            }
                        },
                    ) { picked ->
                        target = CaptureTarget.Existing(picked.id)
                        requestMicThenRecord()
                    }
                }
            }

            Phase.RECORDING -> RecordingScreen()
            Phase.SENDING -> Message(getString(R.string.capture_sending))
            Phase.SENT -> Message(getString(R.string.capture_sent))
            Phase.EMPTY -> Message(getString(R.string.capture_empty))
            Phase.FAILED -> Message(getString(R.string.capture_failed))
            Phase.DENIED -> Message(getString(R.string.capture_no_mic))
        }

        // Close once the outcome has been read. A capture is a fire-and-forget errand — leaving
        // "Saved" on the wrist until it is dismissed by hand makes a one-gesture action into a
        // two-gesture one. The failures sit longer because they are the ones worth reading, and
        // because nothing anywhere else will say the thought did not survive.
        if (phase.isOutcome) {
            LaunchedEffect(phase) {
                delay(if (phase == Phase.SENT) SENT_LINGER_MS else FAILED_LINGER_MS)
                finish()
            }
        }
    }

    /**
     * The recording screen: how long you have been talking, and one way to stop.
     *
     * <p>Deliberately not a screen you can leave running by accident. The elapsed time is the
     * largest thing on it, the meter underneath moves with your voice — which is the only honest
     * answer to "is this actually hearing me" — and the recorder has a hard ceiling of its own for
     * the case where the answer turns out to be "yes, for nine minutes, from inside a sleeve".
     */
    @Composable
    private fun RecordingScreen() {
        LaunchedEffect(Unit) {
            while (recorder.isRecording) {
                elapsedMs = recorder.elapsedMs
                // Normalised against a shout rather than the full 15-bit range: at arm's length a
                // speaking voice never approaches the ceiling, so scaling to it would give a meter
                // that barely twitches.
                level = (recorder.amplitude() / LOUD_AMPLITUDE).coerceIn(0f, 1f)
                // The recorder stops itself at the maximum duration; nothing else notices.
                if (recorder.stoppedItself) {
                    finishRecording()
                    return@LaunchedEffect
                }
                delay(TICK_MS)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = formatElapsed(elapsedMs),
                style = MaterialTheme.typography.titleLarge,
            )
            LevelMeter(level)
            // Round and red, matching the read screen's stop and for the same reason: it is the
            // one control here, and the colour says what it does before the glyph is read.
            FilledIconButton(
                onClick = { finishRecording() },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier
                    .padding(top = 10.dp)
                    .size(TRANSPORT_BUTTON_DP.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_stop),
                    contentDescription = getString(R.string.capture_stop),
                    modifier = Modifier.size(TRANSPORT_ICON_DP.dp),
                )
            }
        }
    }

    /**
     * "New note" — the one row on this screen that is not a destination.
     *
     * <p>Filled in the primary colour where every row below it is tonal, and carrying the only
     * icon in the list. Both are saying the same thing: the rows are places, this makes one. A
     * tonal row reading "New note" would look like a note somebody had already called that.
     */
    @Composable
    private fun NewNoteButton(onClick: () -> Unit) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(),
            contentPadding = ROW_PADDING,
            modifier = Modifier.fillMaxWidth(),
            icon = {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,   // the label beside it already says this
                    modifier = Modifier.size(18.dp),
                )
            },
        ) {
            Text(
                text = getString(R.string.capture_new_note),
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    /** A row of bars that rise with the voice. Drawn rather than composed: it redraws ten times a
     *  second, and a Canvas is one instruction per bar where a Row of Boxes is a relayout. */
    @Composable
    private fun LevelMeter(level: Float) {
        val colour = MaterialTheme.colorScheme.primary
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(vertical = 6.dp)
        ) {
            val gap = size.width / (METER_BARS * 2f - 1f)
            val lit = (level * METER_BARS).toInt()
            for (bar in 0 until METER_BARS) {
                val x = bar * gap * 2f
                // Every bar is drawn, lit or not — a meter that shrinks to nothing in silence
                // reads as a control that has stopped working.
                val height = if (bar <= lit) size.height else size.height * 0.25f
                drawRect(
                    color = colour.copy(alpha = if (bar <= lit) 1f else 0.3f),
                    topLeft = Offset(x, (size.height - height) / 2f),
                    size = androidx.compose.ui.geometry.Size(gap, height),
                )
            }
        }
    }

    private fun requestMicThenRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            beginRecording()
        } else {
            // Asked here rather than on open: picking a destination is the moment the user has said
            // they want to record, and a permission sheet in front of the list would be asking for
            // the microphone before knowing whether they wanted one.
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun beginRecording() {
        elapsedMs = 0L
        level = 0f
        phase = if (recorder.start()) Phase.RECORDING else Phase.FAILED
    }

    /** Ends the recording and ships it, whether the user tapped Stop or the ceiling was reached. */
    private fun finishRecording() {
        val memo = recorder.stop()
        if (memo == null) {
            // Stopped before the encoder had a frame — a fumbled double tap, most often. Not a
            // failure worth alarming anyone about, but not a saved note either.
            phase = Phase.EMPTY
            return
        }

        phase = Phase.SENDING
        val destination = target
        CoroutineScope(Dispatchers.Main).launch {
            val queued = AudioCaptureSender(this@CaptureActivity)
                .send(memo.file, memo.durationMs, System.currentTimeMillis(), destination)
            // Deleted either way. Kept, it would be a file nothing on this watch can play, reach or
            // ever retry — the Data Layer holds the copy that is going to the phone.
            memo.file.delete()
            phase = if (queued) Phase.SENT else Phase.FAILED
        }
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

    private fun formatElapsed(millis: Long): String {
        val seconds = millis / 1000
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
    }

    private enum class Phase(val isOutcome: Boolean = false) {
        CHOOSING,
        RECORDING,
        SENDING,
        SENT(isOutcome = true),
        EMPTY(isOutcome = true),
        FAILED(isOutcome = true),
        DENIED(isOutcome = true),
    }

    internal companion object {
        /**
         * The round transport buttons, here and on the read screen.
         *
         * <p>48dp is Wear's own floor for something you press — its `CompactButton` stops there
         * too. These were 56dp, which is a comfortable size for a thumb and a large one on a
         * 227dp-wide screen; below 48 they would be smaller than the fingertip aiming at them.
         */
        const val TRANSPORT_BUTTON_DP = 48
        const val TRANSPORT_ICON_DP = 22

        /** Ten a second: fast enough for the meter to look like a voice, slow enough to be free. */
        const val TICK_MS = 100L

        /** What counts as a full meter. A speaking voice at arm's length peaks well below 32767. */
        const val LOUD_AMPLITUDE = 12000f
        const val METER_BARS = 9

        /** Long enough to register as a confirmation, short enough not to be a screen to dismiss. */
        const val SENT_LINGER_MS = 1500L
        const val FAILED_LINGER_MS = 3500L
    }
}
