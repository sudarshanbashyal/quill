package mse.quill.reminders;

import android.content.Context;

import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

import mse.quill.ui.profile.ProfilePreferences;

/**
 * Scheduling for the daily "you have cards due" nudge.
 *
 * <p><b>One-shot work that re-arms itself, not periodic work.</b> {@code PeriodicWorkRequest}
 * cannot be pinned to a wall-clock time: its period is measured from whenever it was enqueued and
 * the system is free to slide each run within a flex window, so "remind me at 20:00" would drift
 * into the afternoon within a week. Instead each run is a {@link OneTimeWorkRequest} whose initial
 * delay is computed to the next occurrence of the chosen time, and {@link StudyReminderWorker}
 * queues the following day's before it finishes. The chain is unique and replaceable, so changing
 * the time or toggling the switch simply re-points it.
 *
 * <p>Recomputing the delay from the calendar every run is also what makes the reminder survive
 * things a fixed 24-hour period would not: a daylight-saving change, the user moving timezone, or
 * the device being off over the scheduled moment.
 */
public final class StudyReminders {

    /** Unique work name, so re-scheduling replaces rather than stacks. */
    static final String WORK_NAME = "quill_study_reminder";

    private StudyReminders() {}

    /**
     * Brings the schedule in line with the preferences: armed for the next occurrence of the
     * chosen time if reminders are on, cancelled if they're off.
     *
     * <p>Safe to call at any time, and deliberately so — it is called when the switch moves, when
     * the time changes, and on every app start. The last of those is the recovery path: a force
     * stop cancels WorkManager's pending jobs on some OEM builds, and without a re-arm the
     * reminder would quietly never fire again while the switch still claimed it was on.
     */
    public static void sync(Context context) {
        Context appContext = context.getApplicationContext();
        if (!ProfilePreferences.notificationsEnabled(appContext)) {
            cancel(appContext);
            return;
        }
        schedule(appContext, ProfilePreferences.reminderHour(appContext),
                ProfilePreferences.reminderMinute(appContext));
    }

    static void schedule(Context context, int hour, int minute) {
        long delay = millisUntilNext(hour, minute, System.currentTimeMillis());

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(StudyReminderWorker.class)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag(WORK_NAME)
                .build();

        // REPLACE, not KEEP: this is called whenever the time changes, and keeping the existing
        // job would leave the reminder firing at the old hour until the next run happened to
        // re-arm it.
        WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request);
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(WORK_NAME);
    }

    /**
     * Milliseconds from {@code now} to the next {@code hour:minute}, which is today if that time
     * hasn't passed and tomorrow if it has.
     *
     * <p>Package-visible for the tests, which is the whole reason this is a static function of its
     * arguments rather than something that reads the clock itself — midnight rollover and
     * "scheduled for one minute ago" are exactly the cases worth pinning down, and neither is
     * reachable if the current time can't be supplied.
     */
    static long millisUntilNext(int hour, int minute, long now) {
        Calendar next = Calendar.getInstance();
        next.setTimeInMillis(now);
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, minute);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);

        // Exactly now counts as passed. Zero delay would run the worker the instant it re-armed
        // itself, and the run that just finished is the one that would be repeating.
        if (next.getTimeInMillis() <= now) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        return next.getTimeInMillis() - now;
    }
}
