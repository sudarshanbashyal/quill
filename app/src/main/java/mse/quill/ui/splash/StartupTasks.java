package mse.quill.ui.splash;

import android.content.Context;

import mse.quill.data.AppExecutors;
import mse.quill.onboarding.Onboarding;

/**
 * The work the app wants finished before its first real screen appears — the thing the splash is
 * actually waiting on.
 *
 * <p>Today that is one question — where to send the user, {@link mse.quill.onboarding.Onboarding}
 * being the thing that answers it — asked here rather than by whichever Activity comes next
 * because it reads the database, and a first frame that has to wait on a disk read is a first
 * frame that stutters. The splash is already waiting, and its logo keeps animating throughout:
 * none of this touches the main thread.
 *
 * <p>Anything else that has to finish before the first real screen belongs in the same {@code
 * diskIO} block below. It is the single background thread every repository uses, so work put there
 * cannot race a query the first screen makes.
 */
public final class StartupTasks {

    /** Notified on the main thread once startup work is done. */
    public interface Callback {
        /**
         * @param showWelcome true if this looks like a first install and the welcome screen is due
         *                    — see {@link mse.quill.onboarding.Onboarding#shouldShowWelcome}.
         */
        void onStartupFinished(boolean showWelcome);
    }

    private StartupTasks() {}

    /**
     * Runs startup work off the main thread and reports back on it.
     *
     * @param context application context — held only for the duration of the work, so pass the
     *                application one rather than the Activity.
     */
    public static void run(Context context, Callback callback) {
        AppExecutors executors = AppExecutors.getInstance();
        executors.diskIO(() -> {
            boolean showWelcome = Onboarding.shouldShowWelcome(context);
            executors.mainThread(() -> callback.onStartupFinished(showWelcome));
        });
    }
}
