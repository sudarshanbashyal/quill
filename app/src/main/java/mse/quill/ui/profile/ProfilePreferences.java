package mse.quill.ui.profile;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * Who the user is to Quill: a display name, and whether they want reminders.
 *
 * <p>Preferences rather than a {@code user} table, because there is no account here and no second
 * user to distinguish from. Quill is a local notebook — the name exists to make Home's greeting
 * theirs, not to identify anyone, and nothing outside the device ever sees it. (When Epic C shares
 * a note to another phone, whatever the P2P layer sends about its author is that layer's business
 * and should stay separate from this.)
 */
public final class ProfilePreferences {

    private static final String PREFS_NAME = "profile_prefs";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_NOTIFICATIONS_ENABLED = "notifications_enabled";
    private static final String KEY_REMINDER_HOUR = "reminder_hour";
    private static final String KEY_REMINDER_MINUTE = "reminder_minute";

    private static final int DEFAULT_REMINDER_HOUR = 20;
    private static final int DEFAULT_REMINDER_MINUTE = 0;

    private ProfilePreferences() {}

    /**
     * The name to greet the user by, or {@code null} if they haven't given one. Null rather than
     * "" so callers get one thing to check — Home falls back to the plain greeting on null, and
     * clearing the field has to reach that same state rather than greeting an empty string.
     */
    public static String displayName(Context context) {
        // Sanitized on the way out too, not only on the way in: a name stored by a build that
        // predates DisplayName's rule is already on disk, and this is the only place that can
        // bring it into line without a migration.
        String name = DisplayName.sanitize(prefs(context).getString(KEY_DISPLAY_NAME, null));
        return TextUtils.isEmpty(name) ? null : name;
    }

    /**
     * Sanitized on the way in as well as filtered at the field, so the stored value obeys
     * {@link DisplayName}'s rule whichever route it arrived by — notably a paste the IME commits
     * wholesale rather than keystroke by keystroke.
     */
    public static void setDisplayName(Context context, String name) {
        String cleaned = DisplayName.sanitize(name);
        prefs(context).edit()
                .putString(KEY_DISPLAY_NAME, TextUtils.isEmpty(cleaned) ? null : cleaned)
                .apply();
    }

    /** Whether the daily study reminder is on. Acted on by {@code StudyReminders}. */
    public static boolean notificationsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFICATIONS_ENABLED, false);
    }

    public static void setNotificationsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply();
    }

    /**
     * When the reminder fires, as hour-of-day and minute.
     *
     * <p>Evening by default. A reminder to revise is useful at the point in the day when there is
     * still time to act on it and the day's other business is done; a morning one arrives while
     * someone is getting out of the door, which is how a nudge becomes something to swipe away.
     */
    public static int reminderHour(Context context) {
        return prefs(context).getInt(KEY_REMINDER_HOUR, DEFAULT_REMINDER_HOUR);
    }

    public static int reminderMinute(Context context) {
        return prefs(context).getInt(KEY_REMINDER_MINUTE, DEFAULT_REMINDER_MINUTE);
    }

    public static void setReminderTime(Context context, int hour, int minute) {
        prefs(context).edit()
                .putInt(KEY_REMINDER_HOUR, hour)
                .putInt(KEY_REMINDER_MINUTE, minute)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** The preference file, so a full wipe can clear it along with everything else. */
    public static String prefsName() {
        return PREFS_NAME;
    }
}
