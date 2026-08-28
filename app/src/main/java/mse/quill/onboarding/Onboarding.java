package mse.quill.onboarding;

import android.content.Context;
import android.content.SharedPreferences;

import mse.quill.data.AppDatabase;

/**
 * Whether the welcome screen is due, and the record that it has been dealt with.
 *
 * <p>One flag, and it is set by <em>both</em> answers: taking the sample content and starting empty
 * are equally an answer, and re-asking someone who chose "start empty" would be the app arguing
 * with them. Nothing here decides what the welcome screen offers — see {@link SampleData} for the
 * content itself.
 */
public final class Onboarding {

    private static final String PREFS_NAME = "onboarding_prefs";
    private static final String KEY_WELCOME_SEEN = "welcome_seen";

    private Onboarding() {}

    /**
     * True when the welcome screen should come up instead of the app. <b>Blocking — call from the
     * disk thread</b> ({@code StartupTasks} is where this belongs, and where the splash waits for
     * the answer).
     *
     * <p>Two conditions, not one. The flag says whether this has been asked before; the database
     * says whether there is anything here already. An existing user updating to a build that has
     * this screen has no flag, and greeting them with "Welcome to Quill" over a notebook they have
     * been keeping for weeks is the failure worth spending a query to avoid. Their flag is written
     * on the way past, so the query happens once and never again.
     */
    public static boolean shouldShowWelcome(Context context) {
        Context appContext = context.getApplicationContext();
        if (prefs(appContext).getBoolean(KEY_WELCOME_SEEN, false)) return false;

        if (AppDatabase.getInstance(appContext).hasAnyContentSync()) {
            markWelcomeSeen(appContext);
            return false;
        }
        return true;
    }

    /**
     * Records that the user has answered the welcome screen, whichever way.
     *
     * <p>{@code commit()} rather than {@code apply()}: the very next thing either answer does is
     * start {@code MainActivity} and finish the welcome screen, and on a first install that is
     * also the moment the process is most likely to be killed for memory. An {@code apply()} still
     * in flight would be lost, and the welcome screen would be waiting again on the next launch —
     * over the sample content it had just created. Blocking, so it belongs off the main thread.
     */
    public static void markWelcomeSeen(Context context) {
        prefs(context.getApplicationContext()).edit().putBoolean(KEY_WELCOME_SEEN, true).commit();
    }

    private static SharedPreferences prefs(Context appContext) {
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
