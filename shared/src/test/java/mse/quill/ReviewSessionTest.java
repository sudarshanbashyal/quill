package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import mse.quill.study.scheduling.FlashcardScheduler;
import mse.quill.data.model.Flashcard;
import mse.quill.study.review.ReviewSession;

/** What happens within a single sitting: the queue, the repeats, and what counts towards the score. */
public class ReviewSessionTest {

    private static final long NOW = 1_800_000_000_000L;

    private static Flashcard card(String id) {
        Flashcard card = new Flashcard();
        card.id = id;
        card.front = id + " front";
        card.back = id + " back";
        FlashcardScheduler.initialise(card, NOW);
        return card;
    }

    private static ReviewSession sessionOf(String... ids) {
        List<Flashcard> cards = new ArrayList<>();
        for (String id : ids) cards.add(card(id));
        return new ReviewSession(cards);
    }

    @Test
    public void correctAnswerRetiresTheCard() {
        ReviewSession session = sessionOf("a", "b");

        session.answer(true);

        assertEquals("b", session.current().id);
        session.answer(true);
        assertTrue(session.isFinished());
        assertNull(session.current());
        assertEquals(2, session.correctFirstTry());
    }

    @Test
    public void missedCardComesBackLaterInTheSession() {
        ReviewSession session = sessionOf("a", "b");

        session.answer(false); // a missed — goes to the back

        assertEquals("b", session.current().id);
        session.answer(true);
        assertFalse("the missed card must still be waiting", session.isFinished());
        assertEquals("a", session.current().id);
    }

    /** A repeat is practice, not evidence — only the first answer feeds the long-term schedule. */
    @Test
    public void onlyTheFirstAnswerToACardCounts() {
        ReviewSession session = sessionOf("a");

        assertTrue(session.isFirstAnswer());
        session.answer(false);

        assertEquals("a", session.current().id);
        assertFalse(session.isFirstAnswer());

        session.answer(true);
        assertTrue(session.isFinished());
        assertEquals("getting it right on the retry doesn't rewrite the miss",
                0, session.correctFirstTry());
        assertEquals(1, session.missed());
    }

    @Test
    public void missingACardTwiceCountsOnce() {
        ReviewSession session = sessionOf("a", "b");

        session.answer(false); // a
        session.answer(false); // b
        session.answer(false); // a again
        session.answer(true);  // b
        session.answer(true);  // a

        assertTrue(session.isFinished());
        assertEquals(0, session.correctFirstTry());
        assertEquals(2, session.missed());
    }

    @Test
    public void progressCountsCardsFinishedWithNotAnswersGiven() {
        ReviewSession session = sessionOf("a", "b", "c");

        assertEquals(0, session.completed());
        session.answer(true);
        assertEquals(1, session.completed());

        session.answer(false); // b requeued — not finished with
        assertEquals(1, session.completed());

        session.answer(true);  // c
        assertEquals(2, session.completed());
        session.answer(true);  // b, at last
        assertEquals(3, session.completed());
    }

    @Test
    public void positionNeverRunsPastTheDeck() {
        ReviewSession session = sessionOf("a", "b");

        assertEquals(1, session.position());
        session.answer(false);
        assertEquals(2, session.position());
        session.answer(false);
        assertEquals("still the same two cards, however many attempts", 2, session.position());
    }

    @Test
    public void anEmptyDeckIsAlreadyFinished() {
        ReviewSession session = new ReviewSession(new ArrayList<>());

        assertTrue(session.isFinished());
        assertNull(session.current());
        assertEquals(0, session.deckSize());
    }

    @Test
    public void dueSelectsOnlyCardsThatHaveComeAround() {
        Flashcard overdue = card("overdue");
        overdue.nextReview = NOW - FlashcardScheduler.DAY_MS;
        Flashcard later = card("later");
        later.nextReview = NOW + 3 * FlashcardScheduler.DAY_MS;
        Flashcard fresh = card("fresh"); // initialise() leaves it due now

        List<Flashcard> due = ReviewSession.due(Arrays.asList(overdue, later, fresh), NOW);

        assertEquals(2, due.size());
        assertEquals("overdue", due.get(0).id);
        assertEquals("fresh", due.get(1).id);
    }

    @Test
    public void earliestDueFindsTheNextCardBack() {
        Flashcard soon = card("soon");
        soon.nextReview = NOW + FlashcardScheduler.DAY_MS;
        Flashcard later = card("later");
        later.nextReview = NOW + 9 * FlashcardScheduler.DAY_MS;

        assertEquals(soon.nextReview, ReviewSession.earliestDue(Arrays.asList(later, soon)));
    }
}
