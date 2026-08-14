package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import mse.quill.data.DueProjection;
import mse.quill.data.model.DueCard;

/** What the watch is sent: the horizon, the ordering, the cap and the trim. */
public class DueProjectionTest {

    private static final TimeZone BERLIN = TimeZone.getTimeZone("Europe/Berlin");

    private static DueCard card(String id, long dueAt) {
        DueCard card = new DueCard();
        card.id = id;
        card.front = "front of " + id;
        card.back = "back of " + id;
        card.dueAt = dueAt;
        return card;
    }

    private static long at(TimeZone zone, int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    private static List<String> idsOf(List<DueCard> cards) {
        List<String> ids = new ArrayList<>();
        for (DueCard card : cards) ids.add(card.id);
        return ids;
    }

    // ── Horizon ────────────────────────────────────────────────────────────

    @Test
    public void horizonIsNextLocalMidnight() {
        long noon = at(BERLIN, 2026, 8, 13, 12, 0);

        assertEquals(at(BERLIN, 2026, 8, 14, 0, 0),
                DueProjection.endOfDayExclusive(noon, BERLIN));
    }

    @Test
    public void horizonAtMidnightIsTheFollowingMidnightNotThisOne() {
        // A projection built exactly at 00:00 covers the day that is starting, not the one that
        // just ended — otherwise it would ship an empty list and the tile would read "caught up"
        // for the whole of the day it was refreshed for.
        long midnight = at(BERLIN, 2026, 8, 13, 0, 0);

        assertEquals(at(BERLIN, 2026, 8, 14, 0, 0),
                DueProjection.endOfDayExclusive(midnight, BERLIN));
    }

    @Test
    public void horizonFollowsTheZoneNotTheDefault() {
        long noonInBerlin = at(BERLIN, 2026, 8, 13, 12, 0);
        TimeZone tokyo = TimeZone.getTimeZone("Asia/Tokyo");

        // 12:00 Berlin is 19:00 Tokyo — same instant, different day boundary, and Tokyo's arrives
        // seven hours sooner.
        assertTrue(DueProjection.endOfDayExclusive(noonInBerlin, tokyo)
                < DueProjection.endOfDayExclusive(noonInBerlin, BERLIN));
    }

    @Test
    public void horizonSurvivesTheShortDstDay() {
        // 2026-03-29 is the spring-forward day in Europe/Berlin: 23 hours long. A version that
        // added 86,400,000ms would land an hour into the 30th.
        long morning = at(BERLIN, 2026, 3, 29, 9, 0);

        long horizon = DueProjection.endOfDayExclusive(morning, BERLIN);

        assertEquals(at(BERLIN, 2026, 3, 30, 0, 0), horizon);
        assertEquals(23 * 60 * 60 * 1000L, horizon - at(BERLIN, 2026, 3, 29, 0, 0));
    }

    // ── Selection ──────────────────────────────────────────────────────────

    @Test
    public void takesCardsComingDueLaterToday() {
        long nineAm = at(BERLIN, 2026, 8, 13, 9, 0);
        long horizon = DueProjection.endOfDayExclusive(nineAm, BERLIN);
        List<DueCard> candidates = new ArrayList<>();
        candidates.add(card("overdue", nineAm - 60_000));
        candidates.add(card("later-today", at(BERLIN, 2026, 8, 13, 17, 0)));
        candidates.add(card("tomorrow", at(BERLIN, 2026, 8, 14, 9, 0)));

        List<DueCard> selected = DueProjection.select(candidates, horizon);

        // The whole point of the horizon: "later-today" is not due at 09:00 but is sent anyway.
        assertEquals(2, selected.size());
        assertTrue(idsOf(selected).contains("later-today"));
        assertFalse(idsOf(selected).contains("tomorrow"));
    }

    @Test
    public void ordersMostOverdueFirst() {
        long now = at(BERLIN, 2026, 8, 13, 9, 0);
        long horizon = DueProjection.endOfDayExclusive(now, BERLIN);
        List<DueCard> candidates = new ArrayList<>();
        candidates.add(card("recent", now - 1_000));
        candidates.add(card("ancient", now - 900_000));
        candidates.add(card("middling", now - 60_000));

        assertEquals(java.util.Arrays.asList("ancient", "middling", "recent"),
                idsOf(DueProjection.select(candidates, horizon)));
    }

    @Test
    public void capsAtMaxCardsKeepingTheMostOverdue() {
        long now = at(BERLIN, 2026, 8, 13, 9, 0);
        long horizon = DueProjection.endOfDayExclusive(now, BERLIN);
        List<DueCard> candidates = new ArrayList<>();
        // Built newest-first so the cap can only pass by sorting rather than by luck of insertion.
        for (int i = 0; i < DueProjection.MAX_CARDS + 50; i++) {
            candidates.add(card("card-" + i, now - i * 1_000L));
        }

        List<DueCard> selected = DueProjection.select(candidates, horizon);

        assertEquals(DueProjection.MAX_CARDS, selected.size());
        assertEquals("card-" + (DueProjection.MAX_CARDS + 49), selected.get(0).id);
        assertFalse(idsOf(selected).contains("card-0"));
    }

    @Test
    public void trimsLongSidesAndLeavesTheInputAlone() {
        long now = at(BERLIN, 2026, 8, 13, 9, 0);
        StringBuilder essay = new StringBuilder();
        for (int i = 0; i < DueProjection.MAX_TEXT_CHARS + 100; i++) essay.append('x');
        DueCard original = card("wordy", now - 1_000);
        original.back = essay.toString();

        List<DueCard> candidates = new ArrayList<>();
        candidates.add(original);
        DueCard selected = DueProjection.select(
                candidates, DueProjection.endOfDayExclusive(now, BERLIN)).get(0);

        assertEquals(DueProjection.MAX_TEXT_CHARS + DueProjection.ELLIPSIS.length(),
                selected.back.length());
        assertTrue(selected.back.endsWith(DueProjection.ELLIPSIS));
        assertEquals("front of wordy", selected.front);
        // select() copies rather than mutating: the caller's card is still the database's card.
        assertEquals(DueProjection.MAX_TEXT_CHARS + 100, original.back.length());
    }

    @Test
    public void survivesNullsRatherThanShippingHalfACard() {
        long now = at(BERLIN, 2026, 8, 13, 9, 0);
        long horizon = DueProjection.endOfDayExclusive(now, BERLIN);
        DueCard idless = card(null, now - 1_000);
        DueCard textless = card("empty", now - 1_000);
        textless.front = null;
        textless.back = null;
        List<DueCard> candidates = new ArrayList<>();
        candidates.add(idless);
        candidates.add(textless);
        candidates.add(null);

        List<DueCard> selected = DueProjection.select(candidates, horizon);

        assertEquals(1, selected.size());
        assertEquals("empty", selected.get(0).id);
        assertEquals("", selected.get(0).front);
        assertEquals("", selected.get(0).back);
    }

    @Test
    public void emptyAndNullInputsAreEmptyProjections() {
        assertTrue(DueProjection.select(null, 0).isEmpty());
        assertTrue(DueProjection.select(new ArrayList<>(), 0).isEmpty());
    }
}
