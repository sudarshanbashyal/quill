package mse.quill.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;


/**
 * The rungs of {@link RelativeTime}'s ladder and, mostly, the edges between them.
 *
 * <p>Only the bucket choice is tested, not the wording — the wording is a resource lookup. The
 * boundaries are what actually went wrong before: the old {@code DateUtils} call floored a
 * seconds-old note to "0 minutes ago", which is the first case below.
 */
public class RelativeTimeTest {

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;

    /** A fixed, unambiguous reference point: midday, so "yesterday" tests can move hours around
     *  it without accidentally crossing two midnights. */
    private static long middayOn(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month, day, 12, 0, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static RelativeTime.Bucket past(long now, long ago) {
        return RelativeTime.bucket(ago, now - ago, now);
    }

    @Test
    public void secondsOldReadsAsNow() {
        long now = middayOn(2026, Calendar.JUNE, 12);
        // The regression this class exists for: 30s used to render as "0 minutes ago".
        assertEquals(RelativeTime.Bucket.NOW, past(now, 30 * SECOND));
        assertEquals(RelativeTime.Bucket.NOW, past(now, 59 * SECOND));
    }

    @Test
    public void oneMinuteIsTheFirstMinuteBucket() {
        long now = middayOn(2026, Calendar.JUNE, 12);
        assertEquals(RelativeTime.Bucket.MINUTES, past(now, MINUTE));
        assertEquals(RelativeTime.Bucket.MINUTES, past(now, 59 * MINUTE));
    }

    @Test
    public void oneHourIsTheFirstHourBucket() {
        long now = middayOn(2026, Calendar.JUNE, 12);
        assertEquals(RelativeTime.Bucket.HOURS, past(now, HOUR));
        assertEquals(RelativeTime.Bucket.HOURS, past(now, 23 * HOUR));
    }

    /** Past 24h the calendar decides, so this is one day back from midday — yesterday by both
     *  measures. */
    @Test
    public void oneDayBackIsYesterday() {
        long now = middayOn(2026, Calendar.JUNE, 12);
        assertEquals(RelativeTime.Bucket.ADJACENT_DAY, past(now, 24 * HOUR));
    }

    /**
     * 30 hours before midday is 6am the previous day — still yesterday. 40 hours is 8pm two days
     * back, which is not, and this is exactly the case a purely elapsed-time ladder gets wrong.
     */
    @Test
    public void elapsedHoursDoNotDecideTheDay() {
        long now = middayOn(2026, Calendar.JUNE, 12);
        assertEquals(RelativeTime.Bucket.ADJACENT_DAY, past(now, 30 * HOUR));
        assertEquals(RelativeTime.Bucket.DATE, past(now, 40 * HOUR));
    }

    @Test
    public void anythingOlderIsADate() {
        long now = middayOn(2026, Calendar.JUNE, 12);
        assertEquals(RelativeTime.Bucket.DATE, past(now, 3 * 24 * HOUR));
        assertEquals(RelativeTime.Bucket.DATE, past(now, 400L * 24 * HOUR));
    }

    /** A record stamped slightly ahead of the clock — the reader should see "now", not a date. */
    @Test
    public void futureTimestampInThePastLadderReadsAsNow() {
        long now = middayOn(2026, Calendar.JUNE, 12);
        assertEquals(RelativeTime.Bucket.NOW, past(now, -5 * SECOND));
    }

    @Test
    public void forwardLadderMirrorsTheBackwardOne() {
        long now = middayOn(2026, Calendar.JUNE, 12);
        assertEquals(RelativeTime.Bucket.NOW, RelativeTime.bucket(30 * SECOND, now, now + 30 * SECOND));
        assertEquals(RelativeTime.Bucket.MINUTES, RelativeTime.bucket(5 * MINUTE, now, now + 5 * MINUTE));
        assertEquals(RelativeTime.Bucket.HOURS, RelativeTime.bucket(3 * HOUR, now, now + 3 * HOUR));
        assertEquals(RelativeTime.Bucket.ADJACENT_DAY,
                RelativeTime.bucket(24 * HOUR, now, now + 24 * HOUR));
        assertEquals(RelativeTime.Bucket.DATE,
                RelativeTime.bucket(5 * 24 * HOUR, now, now + 5 * 24 * HOUR));
    }
}
