package mse.quill.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mse.quill.data.model.DueCard
import mse.quill.data.model.Flashcard
import mse.quill.ui.flashcards.ReviewSession

/**
 * Review on the wrist: pick a deck, then front → tap to flip → right or wrong.
 *
 * <p>The session itself is a thin view over [ReviewSession], the same class the phone's review
 * screen drives — shared rather than reimplemented, so the "a missed card comes back before the
 * session ends" rule cannot drift between the two. The watch adds no scheduling of its own: an
 * answer is sent to the phone as an event and the phone's `FlashcardScheduler` decides when the
 * card returns.
 *
 * <p>The deck picker exists because the tile can only ever show one number. "10 cards due" across
 * four notes is a count you cannot act on — it does not tell you whether that is one deck worth
 * clearing or four you will not finish. There is still no "review anyway" here, unlike the phone:
 * a wrist session is a queue, not a browser.
 */
class ReviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ReviewScreen() } }
    }

    @Composable
    private fun ReviewScreen() {
        val context = this
        val scope = rememberCoroutineScope()
        val sender = remember { AnswerSender(context) }

        var loaded by remember { mutableStateOf(false) }
        // null means the phone has never published to this watch — distinct from "published, and
        // there is nothing due". See DueProjectionClient.read; collapsing the two would have this
        // screen congratulate a watch that has never synced.
        var decks by remember { mutableStateOf<List<DueDeck>?>(null) }
        // Decks whose cards are all still to come. Shown alongside, greyed, so an empty picker
        // says "in twenty minutes" instead of leaving the user to guess at "tomorrow".
        var later by remember { mutableStateOf<List<DueDeck>>(emptyList()) }
        var chosen by remember { mutableStateOf<DueDeck?>(null) }
        var session by remember { mutableStateOf<ReviewSession?>(null) }
        var flipped by remember { mutableStateOf(false) }
        // ReviewSession is mutable and not observable, so a counter is what tells Compose the
        // queue moved. Incremented on every answer; nothing reads its value.
        var turn by remember { mutableStateOf(0) }
        // Whether anything was answered on this visit — the difference between "you have finished"
        // and "there was nothing to do", which are the same empty screen and not the same news.
        var reviewedAnything by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            val snapshot = DueProjectionClient(context).read()
            val now = System.currentTimeMillis()
            decks = snapshot?.decks(now)
            later = snapshot?.upcoming(now).orEmpty()
            loaded = true
        }

        val available = decks
        val deck = chosen
        val active = session

        when {
            !loaded -> Centered { CircularProgressIndicator() }

            available == null -> Message(getString(R.string.review_no_phone))

            available.isEmpty() && later.isEmpty() -> Message(
                getString(
                    if (reviewedAnything) R.string.review_all_done
                    else R.string.review_nothing_due
                )
            )

            deck == null || active == null -> DeckPicker(available, later) { picked ->
                chosen = picked
                session = ReviewSession(picked.cards.map(::asFlashcard))
                flipped = false
            }

            else -> {
                // Read through `turn` so the card recomposes when the queue advances.
                @Suppress("UNUSED_EXPRESSION") turn
                val card = active.current()
                if (card == null) {
                    // Deck cleared. Drop it and go back to the picker, which lands on the
                    // caught-up message by itself once the last one goes.
                    LaunchedEffect(deck.noteId) {
                        decks = available.filterNot { it.noteId == deck.noteId }
                        chosen = null
                        session = null
                    }
                    Centered { CircularProgressIndicator() }
                    return@ReviewScreen
                }

                CardFace(
                    deck = deck,
                    session = active,
                    card = card,
                    flipped = flipped,
                    onFlip = { flipped = true },
                    onAnswer = { correct ->
                        answer(active, card, correct, scope, sender)
                        flipped = false
                        turn++
                        reviewedAnything = true
                    },
                )
            }
        }
    }

    /**
     * Title, and either the number due under it or when the deck opens — the whole reason this
     * screen exists.
     *
     * <p>The two kinds are one list rather than two sections. They are the same question asked at
     * different times ("what can I study"), the answer to the second is usually one row long, and a
     * heading over a single greyed row is more furniture than a watch can afford. A deck with
     * nothing due yet is shown disabled rather than left out: there is no "review anyway" on the
     * wrist — a wrist session is a queue, not a browser — so the row is there to be read, not
     * pressed.
     */
    @Composable
    private fun DeckPicker(
        due: List<DueDeck>,
        later: List<DueDeck>,
        onPick: (DueDeck) -> Unit,
    ) {
        PickerList(
            items = due + later,
            label = { it.title },
            subtitle = { deck ->
                val nextDueAt = deck.nextDueAt
                if (nextDueAt == null) {
                    resources.getQuantityString(
                        R.plurals.review_deck_due, deck.cards.size, deck.cards.size
                    )
                } else {
                    getString(R.string.review_deck_next, relativeTime(nextDueAt))
                }
            },
            header = getString(
                if (due.isEmpty()) R.string.review_pick_none_yet else R.string.review_pick_deck
            ),
            enabled = { it.nextDueAt == null },
            onPick = onPick,
        )
    }

    /**
     * How long until [instant], as a watch would say it.
     *
     * <p>Minutes below the hour and whole hours above it. The projection's horizon is the end of
     * the local day, so this never has to reach for days — and rounding up rather than down is
     * what stops a card twenty seconds away from reading "due in 0 min".
     */
    private fun relativeTime(instant: Long): String {
        val millis = (instant - System.currentTimeMillis()).coerceAtLeast(0L)
        val minutes = ((millis + 59_999L) / 60_000L).toInt()
        if (minutes < 60) {
            return resources.getQuantityString(R.plurals.review_in_minutes, minutes, minutes)
        }
        val hours = (minutes + 59) / 60
        return resources.getQuantityString(R.plurals.review_in_hours, hours, hours)
    }

    @Composable
    private fun CardFace(
        deck: DueDeck,
        session: ReviewSession,
        card: Flashcard,
        flipped: Boolean,
        onFlip: () -> Unit,
        onAnswer: (Boolean) -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The flip target is the whole screen, not the words. A one-word front ("Paris")
                // is a tiny hit area, and on a round screen the text sits in the middle with dead
                // space all around it — asking someone to hit the text is asking them to look.
                // Disabled once flipped so the taps that follow belong to the answer buttons.
                .clickable(enabled = !flipped) { onFlip() }
                .padding(horizontal = 12.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = deck.title,
                textAlign = TextAlign.Center,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = getString(
                    R.string.review_position, session.position(), session.deckSize()
                ),
                style = MaterialTheme.typography.labelSmall,
            )

            // weight(1f) so the text takes what is left after the header and the buttons, and
            // scrolls inside that — rather than growing and pushing the buttons off the bottom.
            // A card can run to DueProjection.MAX_TEXT_CHARS (240) a side, which overflows a
            // watch easily, and the answer must never be somewhere you have to scroll to reach.
            //
            // A fresh scroll state per card *and* per face: remembered against both, so turning
            // over a long front does not leave the back already scrolled halfway down, showing
            // its middle. Reset by re-creation rather than by scrolling back to zero, which would
            // animate visibly on the flip.
            val scroll = remember(card.id, flipped) { ScrollState(0) }

            // The crown and the rotating bezel. Rotary is not part of verticalScroll — it is
            // delivered to whichever composable holds focus, so the modifier needs a
            // FocusRequester and that focus has to be actively taken. Re-requested per card and
            // face, because the Box above is recreated with the scroll state and focus does not
            // survive that.
            val rotaryFocus = remember(card.id, flipped) { FocusRequester() }
            LaunchedEffect(card.id, flipped) { rotaryFocus.requestFocus() }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .rotaryScrollable(
                        behavior = RotaryScrollableDefaults.behavior(scrollableState = scroll),
                        focusRequester = rotaryFocus,
                    )
                    .verticalScroll(scroll)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (flipped) card.back else card.front,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            if (!flipped) {
                Text(
                    text = getString(R.string.review_tap_to_flip),
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        12.dp, Alignment.CenterHorizontally
                    ),
                ) {
                    AnswerButton(getString(R.string.review_missed), Icons.Filled.Close) {
                        onAnswer(false)
                    }
                    AnswerButton(getString(R.string.review_got_it), Icons.Filled.Check) {
                        onAnswer(true)
                    }
                }
            }
        }
    }

    /**
     * Records the answer locally and ships it to the phone.
     *
     * <p>[ReviewSession.isFirstAnswer] is read *before* `answer` mutates the queue, and only a
     * first answer is sent. A card missed and then cleared two cards later is practice, not
     * evidence — sending the repeat would look to SM-2 like a clean recall and push the card
     * weeks out, which is the same rule the phone's screen follows.
     */
    private fun answer(
        session: ReviewSession,
        card: Flashcard,
        correct: Boolean,
        scope: CoroutineScope,
        sender: AnswerSender,
    ) {
        val counts = session.isFirstAnswer
        val answeredAt = System.currentTimeMillis()
        session.answer(correct)

        // Not awaited: the queue has already moved and the next card is on screen. A failed send
        // costs this card's schedule advance and nothing else.
        if (counts) scope.launch { sender.send(card.id, correct, answeredAt) }
    }

    /**
     * <p>Icon only, and sized square rather than by weight: with no text to fit there is nothing
     * to reflow, so the pair keeps the same shape from a 384px screen to a 454px one. A tick and a
     * cross also sidestep the problem that killed two earlier layouts here — "Missed" is a word
     * long enough to wrap or clip beside an icon, and shortening it was the wrong fix.
     *
     * <p>[label] survives as the content description: with the word gone from the screen it is the
     * only thing a screen reader has to announce.
     */
    @Composable
    private fun AnswerButton(label: String, icon: ImageVector, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(26.dp),
                )
            }
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

    private companion object {
        /**
         * The watch's `DueCard` as the `Flashcard` [ReviewSession] expects.
         *
         * <p>Only the three fields the session touches are filled: it queues by identity and the
         * screen draws the two faces. The SM-2 fields are left at their defaults on purpose —
         * nothing on the watch reads them, and populating them would suggest the wrist has an
         * opinion about a schedule it is not allowed to compute.
         */
        fun asFlashcard(due: DueCard): Flashcard = Flashcard().apply {
            id = due.id
            front = due.front
            back = due.back
        }
    }
}
