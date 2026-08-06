package mse.quill.util;

import android.content.Context;
import android.text.format.DateFormat;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import mse.quill.R;

/**
 * Every timestamp the app shows, phrased the one way.
 *
 * <p>Replaces scattered calls to {@link android.text.format.DateUtils#getRelativeTimeSpanString},
 * which was inconsistent in exactly the place it is read most: a note saved seconds ago came out as
 * <em>"Updated 0 minutes ago"</em>, because that method's minute resolution floors to zero rather
 * than saying so in words.
 *
 * <p>The ladder, deliberately short — a timestamp on a list row is glanced at, not studied:
 * <pre>
 *   under a minute   now
 *   under an hour    5 min ago
 *   same day         3 hours ago
 *   the day before   yesterday
 *   older            12 Jun     (12 Jun 2025 once the year differs)
 * </pre>
 *
 * <p>Under a day is measured in <em>elapsed</em> time, but "yesterday" is a <em>calendar</em>
 * question. Mixing them is what stops "30 hours ago" reading as yesterday when two midnights have
 * passed since, and stops 2am reporting "yesterday" for something three hours old.
 */
public final class RelativeTime {

    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 60 * MINUTE_MS;
    private static final long DAY_MS = 24 * HOUR_MS;

    /**
     * Which rung of the ladder a timestamp lands on.
     *
     * <p>Split out from the wording so the boundaries can be unit-tested on the JVM: choosing the
     * rung is the part with edge cases (the minute cutoff, the day cutoff, and "yesterday" being a
     * calendar question rather than an elapsed one), while turning a rung into a string is a
     * resource lookup that needs a {@code Context} and has nothing to go wrong in it.
     */
    enum Bucket { NOW, MINUTES, HOURS, ADJACENT_DAY, DATE }

    private RelativeTime() {}

    /** A moment in the past — "now", "5 min ago", "3 hours ago", "yesterday", "12 Jun". */
    public static String past(Context context, long timestamp) {
        return past(context, timestamp, System.currentTimeMillis());
    }

    static String past(Context context, long timestamp, long now) {
        long elapsed = now - timestamp;
        switch (bucket(elapsed, timestamp, now)) {
            case NOW: return context.getString(R.string.time_now);
            case MINUTES:
                return context.getString(R.string.time_minutes_ago, (int) (elapsed / MINUTE_MS));
            case HOURS: {
                int hours = (int) (elapsed / HOUR_MS);
                return context.getResources()
                        .getQuantityString(R.plurals.time_hours_ago, hours, hours);
            }
            case ADJACENT_DAY: return context.getString(R.string.time_yesterday);
            default: return shortDate(context, timestamp);
        }
    }

    /** A moment ahead — "now", "in 5 min", "in 3 hours", "tomorrow", "12 Jun". Used for the next
     *  review a deck is waiting on. */
    public static String future(Context context, long timestamp) {
        return future(context, timestamp, System.currentTimeMillis());
    }

    static String future(Context context, long timestamp, long now) {
        long remaining = timestamp - now;
        switch (bucket(remaining, now, timestamp)) {
            case NOW: return context.getString(R.string.time_now);
            case MINUTES:
                return context.getString(R.string.time_in_minutes, (int) (remaining / MINUTE_MS));
            case HOURS: {
                int hours = (int) (remaining / HOUR_MS);
                return context.getResources()
                        .getQuantityString(R.plurals.time_in_hours, hours, hours);
            }
            case ADJACENT_DAY: return context.getString(R.string.time_tomorrow);
            default: return shortDate(context, timestamp);
        }
    }

    /**
     * @param distance signed millis between the two moments; negative (a clock skew, or a record
     *                 stamped a moment ahead) reads as "now" rather than as a date.
     * @param earlier  the earlier of the two moments, for the calendar-day comparison.
     * @param later    the later of the two.
     */
    static Bucket bucket(long distance, long earlier, long later) {
        if (distance < MINUTE_MS) return Bucket.NOW;
        if (distance < HOUR_MS) return Bucket.MINUTES;
        if (distance < DAY_MS) return Bucket.HOURS;
        return calendarDaysBetween(earlier, later) == 1 ? Bucket.ADJACENT_DAY : Bucket.DATE;
    }

    /**
     * {@code 12 Jun}, or {@code 12 Jun 2025} when the year isn't the current one — a year is noise
     * on a note from last week and essential on one from two years ago.
     *
     * <p>The pattern comes from {@code getBestDateTimePattern} rather than a literal, so the field
     * order follows the locale (a US device gets {@code Jun 12}).
     */
    public static String shortDate(Context context, long timestamp) {
        Locale locale = Locale.getDefault();
        boolean sameYear = yearOf(timestamp) == yearOf(System.currentTimeMillis());
        String skeleton = sameYear ? "dMMM" : "dMMMyyyy";
        CharSequence pattern = DateFormat.getBestDateTimePattern(locale, skeleton);
        return DateFormat.format(pattern.toString(), new Date(timestamp)).toString();
    }

    /**
     * Whole calendar days from {@code earlier} to {@code later}, ignoring the time of day.
     *
     * <p>Rounded, not truncated: midnight to midnight is 23 or 25 hours across a daylight-saving
     * change, so dividing would report the day before a clock change as either today or two days
     * back — a bug that surfaces twice a year and nowhere else.
     */
    private static int calendarDaysBetween(long earlier, long later) {
        long diff = midnightOf(later).getTimeInMillis() - midnightOf(earlier).getTimeInMillis();
        return (int) Math.round(diff / (double) DAY_MS);
    }

    private static Calendar midnightOf(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    private static int yearOf(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        return calendar.get(Calendar.YEAR);
    }
}
