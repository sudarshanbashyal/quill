package mse.quill.study.scheduling;

import mse.quill.data.model.Flashcard;

/**
 * SM-2 spaced repetition, driven by a two-way answer.
 *
 * <p>SM-2 grades an answer 0–5. Quill's review screen offers two buttons, not six, so the grades
 * are fixed at the two ends of the "recalled it" boundary the algorithm actually turns on: 5 for
 * correct, 2 for wrong. Asking someone to rate their own recall on a six-point scale mid-review is
 * the part of SM-2 people get wrong most often and the part that matters least — the interval
 * schedule below does the work, and a self-graded 3-vs-4 mostly adds noise to the easiness factor.
 *
 * <p>The schedule: a card answered correctly is shown again after 1 day, then 6, then at
 * {@code interval × easiness} — so well-known cards fall away quickly while the easiness factor
 * keeps stubborn ones close. A wrong answer sends the card back to the start (due tomorrow) and
 * lowers its easiness, so it comes back sooner every time it's missed. Easiness is floored at
 * {@link #MIN_EASINESS}: below that a card's interval stops growing meaningfully and the schedule
 * degenerates into daily drilling.
 *
 * <p>Pure state transitions on purpose — no clock, no database, no Android — so the schedule can be
 * tested directly.
 */
public final class FlashcardScheduler {

    public static final double DEFAULT_EASINESS = 2.5;
    public static final double MIN_EASINESS = 1.3;

    /** A card that has never been reviewed is due immediately, not a day out. */
    public static final int FIRST_INTERVAL_DAYS = 1;
    public static final int SECOND_INTERVAL_DAYS = 6;

    public static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private static final int QUALITY_CORRECT = 5;
    private static final int QUALITY_INCORRECT = 2;

    private FlashcardScheduler() {}

    /** Fills in the scheduling state a freshly generated card starts life with: due now, unseen. */
    public static void initialise(Flashcard card, long now) {
        card.interval = FIRST_INTERVAL_DAYS;
        card.repetitions = 0;
        card.easiness = DEFAULT_EASINESS;
        card.nextReview = now;
        card.lastReviewedAt = null;
    }

    /** Advances the card's schedule in place for an answer given at {@code now}. */
    public static void applyReview(Flashcard card, boolean correct, long now) {
        int quality = correct ? QUALITY_CORRECT : QUALITY_INCORRECT;

        card.easiness = nextEasiness(card.easiness, quality);

        if (!correct) {
            // Back to the start of the ladder. The lowered easiness is what carries the memory of
            // the lapse forward — the interval alone would forget it happened.
            card.repetitions = 0;
            card.interval = FIRST_INTERVAL_DAYS;
        } else {
            card.repetitions += 1;
            if (card.repetitions == 1) {
                card.interval = FIRST_INTERVAL_DAYS;
            } else if (card.repetitions == 2) {
                card.interval = SECOND_INTERVAL_DAYS;
            } else {
                card.interval = (int) Math.max(1, Math.round(card.interval * card.easiness));
            }
        }

        card.lastReviewedAt = now;
        card.nextReview = now + card.interval * DAY_MS;
    }

    /**
     * SM-2's easiness update: {@code EF' = EF + (0.1 − (5−q)(0.08 + (5−q)·0.02))}. A correct answer
     * nudges it up by 0.1, a wrong one drops it by 0.32.
     */
    private static double nextEasiness(double easiness, int quality) {
        double base = easiness <= 0 ? DEFAULT_EASINESS : easiness; // rows written before defaults
        int miss = 5 - quality;
        double updated = base + (0.1 - miss * (0.08 + miss * 0.02));
        return Math.max(MIN_EASINESS, updated);
    }
}
