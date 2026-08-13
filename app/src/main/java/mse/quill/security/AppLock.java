package mse.quill.security;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import mse.quill.R;

/**
 * The optional lock over the whole app: the toggle's state, when the gate is due, and the prompt
 * that opens it.
 *
 * <p><b>What this is and isn't.</b> It is an access gate, not encryption. {@code quill.db} stays
 * plaintext on disk whether the lock is on or off, so anything holding the file — adb, a backup, a
 * rooted device — reads the notes regardless. What it defends against is the realistic case: a
 * phone already unlocked and in someone else's hand. Per-collection locking (Epic B) is where the
 * content actually gets encrypted, and that has to keep being true independently of this switch —
 * this one must never become the reason a locked collection is left in the clear.
 *
 * <p><b>One authenticator, not two.</b> {@code BIOMETRIC_STRONG | DEVICE_CREDENTIAL} means a
 * fingerprint or face where enrolled, and the phone's own lock-screen PIN everywhere else. Quill
 * deliberately does not mint a passcode of its own: a second secret is one more thing to forget,
 * and forgetting it would need a recovery path that either weakens the lock or loses the notes.
 * Borrowing the device credential also leaves the door open for Epic B, where a Keystore key gated
 * on {@code setUserAuthenticationRequired(true)} can only be released by this same set.
 *
 * <p><b>Session state is static</b> because the lock is a property of the process, not of an
 * Activity instance — a configuration change recreates {@link mse.quill.MainActivity} and must not
 * re-prompt, while a genuinely fresh process must.
 */
public final class AppLock {

    private static final String PREFS_NAME = "security_prefs";
    private static final String KEY_ENABLED = "app_lock_enabled";
    private static final String KEY_GRACE_MILLIS = "app_lock_grace_millis";

    /** Grace-period choices offered on the Profile screen, in milliseconds. */
    public static final long GRACE_IMMEDIATELY = 0L;
    public static final long GRACE_ONE_MINUTE = 60_000L;
    public static final long GRACE_FIVE_MINUTES = 5 * 60_000L;

    /**
     * A minute, not zero. Leaving to pick a file to import, to copy something out of another app,
     * or to answer a notification all stop the Activity, so "Immediately" re-prompts on the way
     * back from each of them — which is the behaviour that gets app locks switched off. A minute
     * covers the round trip while still locking by the time the phone is out of the user's hands.
     */
    private static final long DEFAULT_GRACE_MILLIS = GRACE_ONE_MINUTE;

    private static final int AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    /** Whether the gate has been passed in this process. False on a cold start, so one is due. */
    private static boolean unlocked;

    /**
     * When the app was last backgrounded, on {@link SystemClock#elapsedRealtime()}; -1 while it is
     * in the foreground. Elapsed-realtime rather than wall clock so that changing the system time
     * — or crossing a timezone — can't stretch the grace period into an unlock.
     */
    private static long backgroundedAt = -1;

    private AppLock() {}

    // ---------- Preference state ----------

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static long graceMillis(Context context) {
        return prefs(context).getLong(KEY_GRACE_MILLIS, DEFAULT_GRACE_MILLIS);
    }

    public static void setGraceMillis(Context context, long millis) {
        prefs(context).edit().putLong(KEY_GRACE_MILLIS, millis).apply();
    }

    /**
     * Whether this device can satisfy the prompt at all — a fingerprint/face enrolled, or failing
     * that a PIN, pattern or password on the lock screen. A phone with no screen lock has nothing
     * to authenticate against, so the toggle is shown disabled rather than offered and then failing
     * at the moment it matters.
     *
     * <p>The keyguard is consulted as a second opinion because {@code canAuthenticate} answers for
     * the biometric stack first: a device with no sensor can still report a status other than
     * success while having a perfectly usable PIN, and that PIN is a legitimate way through this
     * gate.
     */
    public static boolean isAvailable(Context context) {
        int status = BiometricManager.from(context).canAuthenticate(AUTHENTICATORS);
        if (status == BiometricManager.BIOMETRIC_SUCCESS) return true;

        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isDeviceSecure();
    }

    // ---------- Session state ----------

    /**
     * Whether the gate is due now. Called as the app comes to the foreground.
     *
     * <p>The three cases, in order: the lock is off; the process is fresh (or was locked
     * explicitly) and has never been unlocked; the app has been away long enough to have gone
     * cold. Anything shorter than the grace period is treated as the user never having left.
     */
    public static boolean shouldPrompt(Context context) {
        if (!isEnabled(context)) return false;
        if (!unlocked) return true;
        if (backgroundedAt < 0) return false;
        return SystemClock.elapsedRealtime() - backgroundedAt >= graceMillis(context);
    }

    /** Starts the grace period. Called when the app stops being visible. */
    public static void onEnteredBackground() {
        if (backgroundedAt < 0) backgroundedAt = SystemClock.elapsedRealtime();
    }

    /** The gate has been passed: stop counting, and don't ask again until the next trip away. */
    public static void markUnlocked() {
        unlocked = true;
        backgroundedAt = -1;
    }

    /**
     * Re-arms the gate for the next foregrounding without prompting now — what turning the toggle
     * <em>off</em> and back on should leave behind, and what a failed authentication leaves the
     * session in.
     */
    public static void lock() {
        unlocked = false;
        backgroundedAt = -1;
    }

    // ---------- The prompt ----------

    /** Outcome of a single {@link #prompt} call. A rejected fingerprint is not an outcome — the
     *  system dialog stays up and lets the user try again — so only success and give-up arrive. */
    public interface Listener {
        void onUnlocked();

        /** @param errorCode one of {@code BiometricPrompt.ERROR_*}. */
        void onFailed(int errorCode, CharSequence message);
    }

    /**
     * Whether a prompt is on screen right now. Static for the same reason the unlock flag is: a
     * rotation destroys and rebuilds the Activity while the dialog stays up, and the rebuilt one
     * has to know not to ask a second time.
     */
    private static boolean promptInFlight;

    public static boolean isPromptInFlight() {
        return promptInFlight;
    }

    /**
     * Binds a listener to an Activity's authentication, and <b>claims it</b>.
     *
     * <p>This is the subtle part of the AndroidX API. The in-flight authentication lives in a
     * retained fragment with an Activity-scoped ViewModel, and that ViewModel holds exactly
     * <em>one</em> callback: the one belonging to the most recently constructed
     * {@code BiometricPrompt} for that host. Construction is therefore not a local act — it takes
     * delivery away from whoever held it before.
     *
     * <p>Two things follow, and both are load-bearing. Construct immediately before
     * {@link #authenticate}, so the caller that is about to show a prompt is the one that hears
     * the answer; Quill has two prompt sites on the same Activity (this gate and the Profile
     * screen's toggle) and the earlier version, which built the gate's prompt once in
     * {@code onCreate}, had its result delivered to the Profile screen's listener the moment the
     * user had visited that tab — the credential was accepted and the gate stayed up over an
     * unlocked app. And re-construct after an Activity is rebuilt under a live prompt, or the
     * answer goes to a listener belonging to a destroyed Activity — same symptom, different cause.
     */
    public static BiometricPrompt createPrompt(FragmentActivity activity, Listener listener) {
        return new BiometricPrompt(activity,
                ContextCompat.getMainExecutor(activity),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        promptInFlight = false;
                        markUnlocked();
                        listener.onUnlocked();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        promptInFlight = false;
                        listener.onFailed(errorCode, errString);
                    }
                });
    }

    /**
     * Claims the callback and shows the system authentication dialog. The only way to raise a
     * prompt — going through {@link #createPrompt} here is what keeps the claim and the showing
     * inseparable.
     *
     * <p>No negative-button text is set, and that is not an oversight: {@code PromptInfo} throws if
     * one is supplied alongside {@code DEVICE_CREDENTIAL}, because the credential fallback is
     * itself what that button would otherwise be.
     */
    public static void authenticate(FragmentActivity activity, Listener listener) {
        promptInFlight = true;
        createPrompt(activity, listener).authenticate(
                new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(activity.getString(R.string.app_lock_prompt_title))
                        .setSubtitle(activity.getString(R.string.app_lock_prompt_subtitle))
                        .setAllowedAuthenticators(AUTHENTICATORS)
                        .build());
    }

    /**
     * Whether the user backing out is what ended the prompt, as opposed to the system refusing to
     * run it. The gate treats the two differently: a cancel leaves the unlock button sitting there
     * to be tapped again, while a lockout has to say why nothing is happening.
     */
    public static boolean isUserCancellation(int errorCode) {
        return errorCode == BiometricPrompt.ERROR_USER_CANCELED
                || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                || errorCode == BiometricPrompt.ERROR_CANCELED;
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
