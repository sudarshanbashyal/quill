package mse.quill.ui.splash;

import android.content.Context;

import mse.quill.data.AppExecutors;

/**
 * The work the app wants finished before its first real screen appears — the thing the splash is
 * actually waiting on.
 *
 * <p>Right now there is none, so this completes as soon as it is asked to and the splash is
 * governed purely by {@code SplashActivity.MINIMUM_DISPLAY_MS}. The indirection is here so that
 * adding a check later is a change to <em>this</em> file only: put the work inside the {@code
 * diskIO} block below (that is the same single background thread every repository uses, so a
 * warm-up here cannot race a query the first screen makes), and the splash will hold until it
 * finishes — the logo keeps animating throughout, because none of this touches the main thread.
 *
 * <p>Candidates when they arrive: opening the Room database, running a migration, priming the
 * due-flashcard counts the home screen asks for, or resolving where to send the user first.
 */
public final class StartupTasks {

    /** Notified on the main thread once startup work is done. */
    public interface Callback {
        void onStartupFinished();
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
            // Startup work goes here. Nothing to do yet.
            executors.mainThread(callback::onStartupFinished);
        });
    }
}
