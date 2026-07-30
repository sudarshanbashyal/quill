package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import mse.quill.data.FlashcardScheduler;
import mse.quill.data.model.Flashcard;

/** The SM-2 schedule behind flashcard review — pure state transitions, no clock and no database. */
public class FlashcardSchedulerTest {

    private static final long NOW = 1_800_000_000_000L;

    private static Flashcard newCard() {
        Flashcard card = new Flashcard();
        card.id = "card-1";
        FlashcardScheduler.initialise(card, NOW);
        return card;
    }

    private static long daysAfter(int days) {
        return NOW + days * FlashcardScheduler.DAY_MS;
    }

    @Test
    public void newCardIsDueImmediately() {
        Flashcard card = newCard();

        assertTrue(card.isDue(NOW));
        assertTrue(card.isNew());
        assertEquals(FlashcardScheduler.DEFAULT_EASINESS, card.easiness, 0.0001);
    }

    @Test
    public void firstCorrectAnswerComesBackTomorrow() {
        Flashcard card = newCard();

        FlashcardScheduler.applyReview(card, true, NOW);

        assertEquals(1, card.repetitions);
        assertEquals(1, card.interval);
        assertEquals(daysAfter(1), card.nextReview);
        assertEquals(Long.valueOf(NOW), card.lastReviewedAt);
    }

    @Test
    public void secondCorrectAnswerJumpsToSixDays() {
        Flashcard card = newCard();

        FlashcardScheduler.applyReview(card, true, NOW);
        FlashcardScheduler.applyReview(card, true, NOW);

        assertEquals(2, card.repetitions);
        assertEquals(6, card.interval);
        assertEquals(daysAfter(6), card.nextReview);
    }

    /** From the third correct answer on, the interval grows by the easiness factor. */
    @Test
    public void laterCorrectAnswersMultiplyByEasiness() {
        Flashcard card = newCard();

        FlashcardScheduler.applyReview(card, true, NOW);
        FlashcardScheduler.applyReview(card, true, NOW);
        double easinessAfterTwo = card.easiness;
        FlashcardScheduler.applyReview(card, true, NOW);

        assertEquals(Math.round(6 * card.easiness), card.interval);
        assertTrue("easiness should have risen", card.easiness > easinessAfterTwo);
    }

    @Test
    public void wrongAnswerSendsTheCardBackToTheStart() {
        Flashcard card = newCard();
        FlashcardScheduler.applyReview(card, true, NOW);
        FlashcardScheduler.applyReview(card, true, NOW);

        FlashcardScheduler.applyReview(card, false, NOW);

        assertEquals(0, card.repetitions);
        assertEquals(1, card.interval);
        assertEquals(daysAfter(1), card.nextReview);
    }

    /** A missed card doesn't only restart — it stays harder, so it climbs back more slowly. */
    @Test
    public void wrongAnswerLowersEasiness() {
        Flashcard card = newCard();

        FlashcardScheduler.applyReview(card, false, NOW);

        assertEquals(FlashcardScheduler.DEFAULT_EASINESS - 0.32, card.easiness, 0.0001);
    }

    @Test
    public void easinessNeverFallsBelowTheFloor() {
        Flashcard card = newCard();

        for (int i = 0; i < 20; i++) {
            FlashcardScheduler.applyReview(card, false, NOW);
        }

        assertEquals(FlashcardScheduler.MIN_EASINESS, card.easiness, 0.0001);
    }

    /** A card that keeps being answered right should end up genuinely far out, not creep by days. */
    @Test
    public void repeatedCorrectAnswersPushTheCardWeeksOut() {
        Flashcard card = newCard();

        for (int i = 0; i < 5; i++) {
            FlashcardScheduler.applyReview(card, true, NOW);
        }

        assertTrue("interval was " + card.interval, card.interval > 30);
    }

    /** Rows written before easiness had a default would otherwise multiply the interval by zero. */
    @Test
    public void missingEasinessFallsBackToTheDefault() {
        Flashcard card = newCard();
        card.easiness = 0;

        FlashcardScheduler.applyReview(card, true, NOW);

        assertEquals(FlashcardScheduler.DEFAULT_EASINESS + 0.1, card.easiness, 0.0001);
    }
}
