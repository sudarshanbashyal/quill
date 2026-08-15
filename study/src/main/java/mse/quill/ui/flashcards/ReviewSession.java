package mse.quill.ui.flashcards;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mse.quill.data.model.Flashcard;

/**
 * One pass through a deck.
 *
 * <p>Two things are happening at once, and they run on different clocks. {@code FlashcardScheduler}
 * decides when a card comes back <em>days</em> from now; this class decides what happens for the
 * rest of the <em>sitting</em>. A card answered wrong goes to the back of the queue and has to be
 * answered right before the session ends — being told the answer and then immediately moving on is
 * how people finish a deck having learned nothing.
 *
 * <p>Only a card's <em>first</em> answer feeds the long-term schedule ({@link #isFirstAnswer}).
 * Without that, missing a card and then getting it right two cards later would look to SM-2 like a
 * clean recall and push it weeks out — the repeat is practice, not evidence.
 *
 * <p>Plain Java, no Android: the session's behaviour is the interesting part and it's testable on
 * its own.
 */
public class ReviewSession {

    private final Deque<Flashcard> queue = new ArrayDeque<>();
    private final Set<String> answered = new HashSet<>();

    private final int deckSize;
    private int correctFirstTry;
    private int missed;

    public ReviewSession(List<Flashcard> cards) {
        queue.addAll(cards);
        deckSize = cards.size();
    }

    /** The card facing the user, or null once the session is done. */
    public Flashcard current() {
        return queue.peekFirst();
    }

    public boolean isFinished() {
        return queue.isEmpty();
    }

    /** True the first time this card is answered in the session — i.e. whether the answer counts. */
    public boolean isFirstAnswer() {
        Flashcard card = current();
        return card != null && !answered.contains(card.id);
    }

    /**
     * Records an answer to the current card and moves on.
     *
     * <p>A missed card goes to the back of the queue. With a one-card deck that means seeing it
     * again immediately — there is nothing to put in between, and stopping the session on a card
     * the user just got wrong would be worse.
     */
    public void answer(boolean correct) {
        Flashcard card = queue.pollFirst();
        if (card == null) return;

        if (!answered.contains(card.id)) {
            answered.add(card.id);
            if (correct) correctFirstTry++;
            else missed++;
        }

        if (!correct) queue.addLast(card);
    }

    /** How many distinct cards the session started with. */
    public int deckSize() {
        return deckSize;
    }

    /** Position of the card on screen, 1-based, counting distinct cards seen so far. */
    public int position() {
        return Math.min(deckSize, answered.size() + (isFinished() ? 0 : 1));
    }

    /** Cards finished with — the progress bar's numerator, not the same as {@link #position()}. */
    public int completed() {
        return deckSize - distinctRemaining();
    }

    public int correctFirstTry() {
        return correctFirstTry;
    }

    /** Cards missed at least once, however many attempts it took to clear them afterwards. */
    public int missed() {
        return missed;
    }

    private int distinctRemaining() {
        Set<String> remaining = new HashSet<>();
        for (Flashcard card : queue) remaining.add(card.id);
        return remaining.size();
    }

    /** The deck this session was built from, for a "review again" that starts over. */
    public static List<Flashcard> due(List<Flashcard> deck, long now) {
        List<Flashcard> due = new ArrayList<>();
        for (Flashcard card : deck) {
            if (card.isDue(now)) due.add(card);
        }
        return due;
    }

    /** When the earliest not-yet-due card comes back around, or 0 if there isn't one. */
    public static long earliestDue(List<Flashcard> deck) {
        long earliest = 0;
        for (Flashcard card : deck) {
            if (earliest == 0 || card.nextReview < earliest) earliest = card.nextReview;
        }
        return earliest;
    }
}
