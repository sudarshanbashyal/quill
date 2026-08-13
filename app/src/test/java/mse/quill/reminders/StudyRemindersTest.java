package mse.quill.reminders;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * The reminder's delay arithmetic — the part that decides whether "remind me at 20:00" actually
 * happens at 20:00.
 *
 * <p>Worth testing on the JVM rather than by waiting for a notification: the interesting cases are
 * the boundaries (the time already gone, the time exactly now, a run that lands over midnight) and
 * none of them are reachable by driving the UI without changing the device clock.
 */
public class StudyRemindersTest {

    private static long at(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.AUGUST, 13, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void schedulesLaterToday_whenTheTimeHasNotPassed() {
        long delay = StudyReminders.millisUntilNext(20, 0, at(9, 30));
        assertEquals(TimeUnit.HOURS.toMillis(10) + TimeUnit.MINUTES.toMillis(30), delay);
    }

    @Test
    public void schedulesTomorrow_whenTheTimeHasPassed() {
        long delay = StudyReminders.millisUntilNext(20, 0, at(21, 0));
        assertEquals(TimeUnit.HOURS.toMillis(23), delay);
    }

    @Test
    public void schedulesTomorrow_whenTheTimeIsExactlyNow() {
        // The run that has just finished is what re-arms the schedule, so "now" must mean
        // tomorrow. A zero delay would fire the worker again immediately, and it would keep doing
        // so — a notification loop, at the one moment of the day the user asked to be left alone
        // afterwards.
        long delay = StudyReminders.millisUntilNext(20, 0, at(20, 0));
        assertEquals(TimeUnit.HOURS.toMillis(24), delay);
    }

    @Test
    public void handlesTheMidnightBoundary() {
        long justBefore = StudyReminders.millisUntilNext(0, 5, at(23, 55));
        assertEquals(TimeUnit.MINUTES.toMillis(10), justBefore);

        long justAfter = StudyReminders.millisUntilNext(23, 55, at(0, 5));
        assertEquals(TimeUnit.HOURS.toMillis(23) + TimeUnit.MINUTES.toMillis(50), justAfter);
    }

    @Test
    public void neverReturnsZeroOrNegative() {
        // Whatever the hour, the delay has to point forward — WorkManager treats a non-positive
        // initial delay as "run now".
        for (int hour = 0; hour < 24; hour++) {
            for (int minute : new int[]{0, 30, 59}) {
                long delay = StudyReminders.millisUntilNext(hour, minute, at(hour, minute));
                assertTrue("hour " + hour + ":" + minute, delay > 0);
            }
        }
    }

    @Test
    public void neverSchedulesMoreThanADayOut() {
        for (int hour = 0; hour < 24; hour++) {
            long delay = StudyReminders.millisUntilNext(hour, 0, at(13, 17));
            assertTrue("hour " + hour, delay > 0 && delay <= TimeUnit.HOURS.toMillis(24));
        }
    }
}
