package mse.quill.ui.splash;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import mse.quill.MainActivity;
import mse.quill.R;
import mse.quill.ui.welcome.WelcomeActivity;

/**
 * The app's entry point: shows the animated Quill mark, then hands off to {@link MainActivity}.
 *
 * <p>Where it hands off to is {@link StartupTasks}' answer: {@link mse.quill.ui.welcome.WelcomeActivity}
 * on what looks like a first install, {@link MainActivity} otherwise. Decided there rather than
 * here so the database read it needs happens on the thread this screen is already waiting for.
 *
 * <p>The hand-off waits on two things, whichever finishes last:
 *
 * <ul>
 *   <li>{@link #MINIMUM_DISPLAY_MS} of wall clock, so the animation is never a flicker on a fast
 *       device; and
 *   <li>{@link StartupTasks}, which is the seam for the background checks this screen is meant to
 *       cover for. It reports done immediately today, so the timer is what governs — but if it
 *       later takes longer than the minimum, the splash simply stays up and keeps animating.
 * </ul>
 *
 * <p>The wait deliberately does <em>not</em> start the next Activity while this one is stopped:
 * Android 10 onwards blocks background activity starts, so if the user leaves mid-splash the
 * hand-off is deferred to the next {@link #onStart()}.
 */
public class SplashActivity extends AppCompatActivity {

    /** How long the logo stays up at minimum, even when there is nothing to wait for. */
    private static final long MINIMUM_DISPLAY_MS = 2_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean minimumElapsed;
    private boolean startupFinished;
    /** Set by {@link StartupTasks} before {@link #startupFinished}, and read only after it. */
    private boolean showWelcome;
    private boolean started;
    private boolean handedOff;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Quill always renders in its own light palette, independent of the system theme. This
        // now has to be set here as well as in MainActivity — this is the first Activity created,
        // and setting it later would re-create an Activity that had already drawn.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        handler.postDelayed(() -> {
            minimumElapsed = true;
            openMainIfReady();
        }, MINIMUM_DISPLAY_MS);

        StartupTasks.run(getApplicationContext(), showWelcome -> {
            this.showWelcome = showWelcome;
            startupFinished = true;
            openMainIfReady();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        openMainIfReady();
    }

    @Override
    protected void onStop() {
        started = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @SuppressWarnings("deprecation") // overridePendingTransition, the only option below API 34
    private void openMainIfReady() {
        if (handedOff || !minimumElapsed || !startupFinished || !started || isFinishing()) return;
        handedOff = true;

        // The two ways to ask for a cross-fade take opposite orderings: the API 34 call has to
        // happen before the transition is queued, the older one immediately after.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                    OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out);
        }
        startActivity(new Intent(this,
                showWelcome ? WelcomeActivity.class : MainActivity.class));
        finish();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }
}
