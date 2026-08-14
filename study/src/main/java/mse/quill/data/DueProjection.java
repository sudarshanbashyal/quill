package mse.quill.data;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import mse.quill.data.model.DueCard;

/**
 * What the watch is allowed to know: today's due cards, ordered, capped, and trimmed to fit.
 *
 * <p>Pure, and in {@code :study} rather than {@code :app}, because every rule here is a decision
 * about the projection rather than about SQLite — the phone's repository does the query and the
 * lock exclusion, then hands the surviving rows through {@link #select}. Splitting it that way is
 * what makes the horizon and the budget testable without a device or a database.
 *
 * <p>What this deliberately does <em>not</em> do is decide which collections are visible. That is
 * {@code NoteCrypto}'s job and it happens in SQL, before anything reaches here — a locked
 * collection's cards must never become {@code DueCard}s in the first place, because by the time
 * they were objects in a list the mistake would already be one filter away from shipping.
 */
public final class DueProjection {

    private DueProjection() {}

    /**
     * The most cards the watch is sent, and the reason it is a small number.
     *
     * <p>A {@code DataItem} caps near 100 KB and the payload is dominated by card text, so the two
     * limits here are one decision: {@value #MAX_CARDS} cards × two sides × {@value #MAX_TEXT_CHARS}
     * characters is ~58 KB of text, plus ~4 KB of UUIDs and a long apiece — comfortably inside the
     * cap with room for the encoding's own overhead.
     *
     * <p>It is also more cards than anyone will answer on a wrist. A watch session is a queue, not
     * a browser (see Epic J), so a cap that truncates a 400-card backlog to its 120 most overdue is
     * describing the feature rather than limiting it.
     */
    public static final int MAX_CARDS = 120;

    /** Per side. Long enough for a question; short enough that 240 of them still fit. */
    public static final int MAX_TEXT_CHARS = 240;

    /** Appended when a side is cut, so the watch shows a truncation rather than implying an end. */
    public static final String ELLIPSIS = "…";

    /**
     * The cards to send: everything due at or before {@code horizon}, most overdue first, capped.
     *
     * <p>Note the comparison is against the <em>horizon</em>, not against "now" — a card coming due
     * at 17:00 is included in a projection built at 09:00. That is what stops the tile from saying
     * "all caught up" for a card that becomes due while the phone is in a pocket; the watch holds
     * the later {@code dueAt} and re-filters against its own clock. See {@link #endOfDayExclusive}.
     */
    public static List<DueCard> select(List<DueCard> candidates, long horizon) {
        List<DueCard> due = new ArrayList<>();
        if (candidates == null) return due;

        for (DueCard card : candidates) {
            if (card == null || card.id == null) continue;
            if (card.dueAt > horizon) continue;
            due.add(trimmed(card));
        }

        // Most overdue first, so a cap that bites drops the cards the user is least behind on.
        Collections.sort(due, (a, b) -> Long.compare(a.dueAt, b.dueAt));

        return due.size() <= MAX_CARDS ? due : new ArrayList<>(due.subList(0, MAX_CARDS));
    }

    /**
     * Midnight at the end of {@code now}'s local day, exclusive — the upper bound of "today".
     *
     * <p>Takes its zone rather than reading the default so the DST and midnight cases can actually
     * be tested, the same reasoning as {@code StudyReminders.millisUntilNext}. Computed from a
     * calendar rather than by rounding to a 24-hour boundary, because a day is not always 86,400
     * seconds long and the days it isn't are exactly the ones a fixed-arithmetic version gets wrong.
     */
    public static long endOfDayExclusive(long now, TimeZone zone) {
        Calendar midnight = Calendar.getInstance(zone);
        midnight.setTimeInMillis(now);
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        midnight.add(Calendar.DAY_OF_YEAR, 1);
        return midnight.getTimeInMillis();
    }

    /**
     * A copy with both sides cut to {@link #MAX_TEXT_CHARS}; the original is left alone.
     *
     * <p><b>Every field of {@link DueCard} has to be copied here.</b> This is a hand-written copy
     * and the compiler will not notice a new field being left out — it just arrives on the watch
     * empty, which reads as a data problem rather than as this method. {@code noteId} and
     * {@code noteTitle} were missed exactly that way, and the symptom was every deck merging into
     * one nameless group. {@code DueProjectionTest} now pins it.
     */
    private static DueCard trimmed(DueCard card) {
        DueCard copy = new DueCard();
        copy.id = card.id;
        copy.front = truncate(card.front);
        copy.back = truncate(card.back);
        copy.dueAt = card.dueAt;
        copy.noteId = card.noteId;
        // Not truncated: a deck name is short by construction, and cutting it would make two decks
        // with a long shared prefix look like the same deck in the picker.
        copy.noteTitle = card.noteTitle;
        return copy;
    }

    private static String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_TEXT_CHARS) return text;
        return text.substring(0, MAX_TEXT_CHARS) + ELLIPSIS;
    }
}
