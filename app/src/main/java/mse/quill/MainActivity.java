package mse.quill;

import android.Manifest;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.biometric.BiometricPrompt;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import mse.quill.data.AppExecutors;
import mse.quill.data.wear.WearNoteListPublisher;
import mse.quill.data.wear.WearProjectionPublisher;
import mse.quill.data.wear.WearReadStatePublisher;
import mse.quill.reminders.StudyReminders;
import mse.quill.security.AppLock;
import mse.quill.security.CollectionLock;
import mse.quill.ui.audio.MiniPlayerView;
import mse.quill.util.PipAware;
import mse.quill.ui.common.SwipeToDelete;
import mse.quill.ui.common.WindowInsetsUtils;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    /** Latest bottom system-bar inset, re-applied whenever the bottom bar is shown or hidden. */
    private int bottomSystemInset;

    /** Whether this session has already put the notification request in front of the user. */
    private boolean notificationPermissionAsked;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // Nothing to do either way: playback already started, and the service copes with
                // having no visible notification.
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Quill always renders in its own light palette, independent of the system theme.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // Only the side insets are the activity's business. The bottom one belongs to whatever
            // reaches the bottom of the screen (see applyBottomInset()), and the top one is left to
            // each screen via WindowInsetsUtils.applyTopInset — padding it here would put a strip
            // of window background behind the status bar on every screen, instead of letting the
            // screen's own header or background run up behind it.
            v.setPadding(systemBars.left, 0, systemBars.right, 0);
            bottomSystemInset = systemBars.bottom;
            applyBottomInset();
            return insets;
        });

        // Before anything reads an intent: restores what was pending and starts watching for
        // Home, so a file or a session link that outlived a rotation is still delivered.
        deepLinks = new DeepLinkRouter(this);
        deepLinks.onCreate(savedInstanceState);

        applyTopInsetToEveryScreen();
        setupNowPlayingBar();
        setupBottomNavigation();
        setupAppLock();
        deepLinks.route(getIntent());

        // Re-arms the daily reminder if it's on. Cheap, idempotent, and the recovery path for a
        // WorkManager queue that a force stop or a "clear data" wiped out — see StudyReminders.
        StudyReminders.sync(this);

        // Third and last publish trigger, alongside the daily worker and answering a card. This is
        // the one that covers a watch paired since the last of those: without it, a freshly paired
        // watch would show nothing until tomorrow morning or the next review, whichever came first.
        AppExecutors.getInstance().diskIO(() -> WearProjectionPublisher.publishSync(this));
        // The watch's note pickers read this. Same reasoning as the line above: a watch paired
        // since the last save would otherwise have nothing to offer until the next one.
        AppExecutors.getInstance().diskIO(() -> WearNoteListPublisher.publishSync(this));
        // What the watch's transport controls are drawn from. Attached here as well as when the
        // watch asks for a reading, so a reading started on the phone — from a note's own menu —
        // is one the wrist can also pause.
        WearReadStatePublisher.ensureAttached(this);
    }

    /** Everything that arrives from outside and names a place to go — see {@link DeepLinkRouter}. */
    private DeepLinkRouter deepLinks;

    /**
     * A {@code .quill}/{@code .quillboard}/{@code .quillpack} opened from outside the app (a file
     * manager, mail client, or Quick Share — see the manifest's intent-filter on this activity).
     *
     * <p>{@code onNewIntent} covers the case where Quill is already running: the default launch
     * mode reuses this instance rather than starting a second one, and without this override the
     * new intent would sit unread in {@link #getIntent()} while the old one keeps being returned.
     */
    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        deepLinks.onNewIntent(intent);
    }

    /** Carries the router's pending file and session link across a rotation. */
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        deepLinks.onSaveInstanceState(outState);
    }

    /**
     * Puts the now-playing bar above the screens and settles who runs up behind the status bar.
     *
     * <p>While the bar is showing it is the topmost thing in the window, so it takes the status-bar
     * inset and the screen below it takes none; when it goes away the screens take it back. Without
     * the handover, one of the two ends up with a status bar's worth of empty space it didn't earn.
     */
    private void setupNowPlayingBar() {
        View root = findViewById(R.id.main);
        MiniPlayerView miniPlayer = findViewById(R.id.mini_player);
        WindowInsetsUtils.applyChromeTopInset(miniPlayer);
        miniPlayer.setVisibilityListener(visible ->
                WindowInsetsUtils.setChromeOwnsTopInset(root, visible));
    }

    /**
     * Asks for notification permission the first time a recording is played, and never otherwise.
     *
     * <p>The audio doesn't need it — the foreground service runs either way, so a locked phone
     * keeps playing. What is lost by refusing is the notification, which is also the lock-screen
     * and shade control. That makes "play" the only moment where the request means anything, so
     * it's the only moment it's made; asking at launch would be a prompt about a feature the user
     * hasn't touched yet.
     */
    /** Same request, for a view that only has a context — a segment deep inside a note is where
     *  the first play actually happens, and it has no handle on the activity. */
    public static void requestPlaybackNotificationPermission(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof MainActivity) {
                ((MainActivity) context).ensurePlaybackNotificationPermission();
                return;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
    }

    public void ensurePlaybackNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (notificationPermissionAsked) return; // One refusal is an answer.
        notificationPermissionAsked = true;
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    /**
     * Gives every screen the status-bar inset as its view is created, so the bar shows the screen
     * rather than a strip of window background — and so no fragment has to remember to ask.
     *
     * <p>A screen takes the inset on its own root unless it implements
     * {@link WindowInsetsUtils.TopInsetHost} and names a different view. {@code recursive = true}
     * because the screens live in the nav host's *child* fragment manager, not this one; the nav
     * host itself is skipped, since padding the container is exactly the white band this avoids,
     * and dialogs are skipped because they are their own windows.
     */
    private void applyTopInsetToEveryScreen() {
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(@NonNull FragmentManager fm,
                                                      @NonNull Fragment fragment,
                                                      @NonNull View view,
                                                      @Nullable Bundle savedInstanceState) {
                        if (fragment instanceof NavHostFragment
                                || fragment instanceof DialogFragment) {
                            return;
                        }
                        View target = fragment instanceof WindowInsetsUtils.TopInsetHost
                                ? ((WindowInsetsUtils.TopInsetHost) fragment).topInsetTarget(view)
                                : view;
                        WindowInsetsUtils.applyTopInset(target);
                    }
                }, true);
    }

    /**
     * Hands the gesture-bar inset to whichever view actually reaches the bottom of the screen.
     *
     * <p>{@link BottomNavigationView} already pads itself by that inset, so the root padding this
     * replaces was charging for it twice: the bar drew its own inset-height strip and then sat on
     * a second, empty one, costing the content above ~24dp for nothing. When the bar is hidden —
     * the editor, a review session — the nav host is what reaches the bottom, so it takes the
     * inset instead and content still clears the gesture pill.
     */
    private void applyBottomInset() {
        View navHost = findViewById(R.id.nav_host_fragment);
        View bottomNav = findViewById(R.id.bottom_nav);
        boolean barVisible = bottomNav.getVisibility() == View.VISIBLE;
        navHost.setPadding(0, 0, 0, barVisible ? 0 : bottomSystemInset);

        // The bar's height is set here rather than in the layout because it has to be the row of
        // items *plus* the inset the bar pads itself by — at wrap_content M3 floors it at 80dp
        // before that padding, which is taller than this app needs and comes straight off the
        // list above it. app:itemPaddingTop/Bottom alone don't move it; the floor wins.
        ViewGroup.LayoutParams params = bottomNav.getLayoutParams();
        int height = getResources().getDimensionPixelSize(R.dimen.bottom_nav_height)
                + bottomSystemInset;
        if (params.height != height) {
            params.height = height;
            bottomNav.setLayoutParams(params);
        }
    }

    // ---------- App lock ----------

    /** The gate over this window's content, and the prompt that opens it. See {@link AppLock}. */
    private View lockGate;
    private TextView lockMessage;
    /** Swallows back while the gate is up; disabled the rest of the time so navigation is normal. */
    private OnBackPressedCallback lockBackCallback;

    /** What the gate does with an answer. A field because it is handed to {@link AppLock} afresh
     *  every time a prompt is raised — see {@link AppLock#createPrompt} for why it can't be bound
     *  once and left. */
    private final AppLock.Listener lockListener = new AppLock.Listener() {
        @Override public void onUnlocked() {
            hideLockGate();
        }

        @Override public void onFailed(int errorCode, CharSequence message) {
            // The gate stays up either way. A cancel just leaves the Unlock button waiting;
            // anything else needs to say why, or the screen looks broken rather than shut.
            if (AppLock.isUserCancellation(errorCode)) {
                lockMessage.setVisibility(View.GONE);
            } else {
                lockMessage.setText(errorCode == BiometricPrompt.ERROR_LOCKOUT
                        || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT
                        ? getString(R.string.app_lock_gate_locked_out)
                        : message);
                lockMessage.setVisibility(View.VISIBLE);
            }
        }
    };

    /**
     * Prepares the gate. Found in {@code onCreate} whether or not the lock is on, because the
     * decision to show it is made in {@code onStart} and there must be nothing left to inflate by
     * then — a gate assembled after the content was already on screen would be a gate the user had
     * a frame to read past.
     */
    private void setupAppLock() {
        lockGate = findViewById(R.id.app_lock_gate);
        lockMessage = findViewById(R.id.app_lock_message);

        // Reclaims delivery after a rotation that happened with the gate's own prompt up.
        // Conditional, and the condition matters: if the gate is not due then any live prompt
        // belongs to the Profile screen's toggle, and claiming it here would swallow that answer.
        if (AppLock.shouldPrompt(this)) AppLock.createPrompt(this, lockListener);

        findViewById(R.id.app_lock_unlock).setOnClickListener(v -> {
            if (!AppLock.isPromptInFlight()) AppLock.authenticate(this, lockListener);
        });

        // While the gate is up, back leaves the app rather than navigating behind it. Popping the
        // nav stack under a locked screen would change what the user finds on unlocking, and
        // finish() would be worse — the task would be gone and with it the back stack, so a
        // ten-second glance at another app would cost the note they had open.
        lockBackCallback = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                moveTaskToBack(true);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, lockBackCallback);
    }

    private void setLockBackCallbackEnabled(boolean enabled) {
        if (lockBackCallback != null) lockBackCallback.setEnabled(enabled);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (AppLock.shouldPrompt(this)) {
            showLockGate();
            // Not if one is already up: a device-credential fallback runs in its own Activity on
            // older platforms, which stops this one and brings it back — without the guard, coming
            // back would stack a second prompt on the first.
            if (!AppLock.isPromptInFlight()) AppLock.authenticate(this, lockListener);
        } else {
            hideLockGate();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (!AppLock.shouldPrompt(this)) {
            hideLockGate();
            // Also here, not only in hideLockGate: the gate may already be down by the time a
            // waiting link is noticed — the device-credential fallback runs in its own Activity,
            // so coming back from it resumes this one rather than dismissing anything.
            deepLinks.onUnlocked();
        }
    }

    /**
     * Keeps the open note out of the thumbnail the system shows in the recents list — otherwise the
     * lock is bypassed by a gesture, the task switcher still displaying whatever was on screen for
     * as long as the app stays in the list.
     *
     * <p>A flag rather than the lock gate, which is what used to cover this. Painting the gate
     * treats every pause as a departure, and most pauses aren't: a share sheet, a file picker, a
     * permission dialog and the app's own biometric prompt all sit in front of a still-running
     * Quill, and each one put "Quill is locked" behind it while the user was in the middle of using
     * Quill. The flag is invisible from the front and applies to the snapshot, which is the only
     * thing that ever needed covering. Coming back is still gated: {@link #onStart} raises the gate
     * before anything is drawn if a prompt is due.
     */
    @Override
    protected void onPause() {
        super.onPause();
        // Not while shrinking into Picture-in-Picture: entering PIP pauses the activity too, and
        // FLAG_SECURE blacks out the surface the system is about to screenshot for that floating
        // window — the board would open into PIP showing nothing at all.
        if (AppLock.isEnabled(this) && !isInPip()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private boolean isInPip() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode();
    }

    /**
     * The whiteboard's PIP toggle, and the same path {@link #onUserLeaveHint} takes when the user
     * leaves (home button / recents) while a board is open — see {@link WhiteboardFragment}.
     *
     * <p>{@code aspectWidth}/{@code aspectHeight} come from the canvas itself, clamped to what
     * {@link PictureInPictureParams} accepts (between 1:2.39 and 2.39:1), so a very tall or very
     * wide board still gets a window shaped roughly like it rather than the system's default.
     */
    public void enterWhiteboardPip(float aspectWidth, float aspectHeight) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return;

        float ratio = aspectWidth / aspectHeight;
        ratio = Math.max(1f / 2.39f, Math.min(ratio, 2.39f));
        // Rational wants whole numbers; scaling up keeps enough precision without the max-int
        // range PictureInPictureParams rejects.
        Rational aspect = new Rational(Math.round(ratio * 1000), 1000);
        PictureInPictureParams params = new PictureInPictureParams.Builder()
                .setAspectRatio(aspect)
                .build();
        try {
            enterPictureInPictureMode(params);
        } catch (IllegalStateException e) {
            // Not resumed, or the manufacturer's PIP is unavailable right now — the button simply
            // does nothing rather than crashing the screen it was pressed from.
        }
    }

    /** Auto-enters PIP the way a video app does: leaving the whiteboard for another app or Recents
     *  shrinks it instead of pausing it out of sight, so the board is still there to glance at. Any
     *  other screen leaves normally — a note or a quiz has nothing useful to show at that size. */
    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        Fragment current = currentPrimaryFragment();
        if (current instanceof mse.quill.ui.whiteboard.WhiteboardFragment) {
            ((mse.quill.ui.whiteboard.WhiteboardFragment) current).enterPipIfPossible();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        Fragment current = currentPrimaryFragment();
        if (current instanceof PipAware) {
            ((PipAware) current).onPipModeChanged(isInPictureInPictureMode);
        }
    }

    /** The screen actually on top inside the nav host — same lookup {@link #runWhenNavHostReady}
     *  and the widget-tap handling use, kept in one place since PIP needs it twice more. */
    private Fragment currentPrimaryFragment() {
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        return host == null ? null
                : host.getChildFragmentManager().getPrimaryNavigationFragment();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Starts the grace period. In onStop rather than onPause because a dialog or the system's
        // own biometric sheet pauses nothing, but they do sit in front of the app, and treating
        // those as "the user left" would re-lock the screen they are standing in front of.
        AppLock.onEnteredBackground();

        // Locked collections get no grace period at all, unlike the app lock above. They hold the
        // material the user went out of their way to encrypt, so leaving the app is enough to shut
        // them — coming back to one always costs a prompt.
        CollectionLock.relockAll();

        // Leaving the app is the moment the home screen is about to be looked at, and it is also
        // the last chance to catch anything that changed without a push of its own. The widgets
        // have no periodic refresh (updatePeriodMillis is 0), so this is a backstop rather than
        // the mechanism: each repository pushes its own change as it makes it. Cheap when there is
        // no widget on the home screen — every call short-circuits on an empty id array.
        mse.quill.widget.WidgetUpdater.notifyAllChanged(this);
    }

    private void showLockGate() {
        if (lockGate == null || lockGate.getVisibility() == View.VISIBLE) return;
        lockMessage.setVisibility(View.GONE);
        lockGate.setVisibility(View.VISIBLE);
        setLockBackCallbackEnabled(true);
    }

    private void hideLockGate() {
        if (lockGate == null || lockGate.getVisibility() == View.GONE) return;
        lockGate.setVisibility(View.GONE);
        setLockBackCallbackEnabled(false);
        // The gate is down, so a session link that was waiting behind it can go ahead.
        deepLinks.onUnlocked();
    }

    /**
     * Wires the top-level destinations to the bottom bar.
     *
     * <p>The menu's item ids <em>are</em> the destination ids, which is what lets {@link
     * NavigationUI} handle selection and the back stack — switching tabs pops back to the start
     * destination rather than stacking Home on Flashcards on Home. The bar then hides itself
     * anywhere deeper: a note editor or a review session is somewhere you arrived from a tab, and
     * offering to jump away mid-note is noise.
     */
    // ── Swiping between tabs ─────────────────────────────────────────────

    /** The bottom bar's items in order, which is also the order a swipe walks them. */
    private static final int[] TAB_DESTINATIONS = {
            R.id.homeFragment,
            R.id.flashcardDecksFragment,
            R.id.quizzesFragment,
            R.id.profileFragment,
    };

    /** How much of the screen's width a drag has to cross to count as turning the page. */
    private static final float SWIPE_TAB_FRACTION = 0.3f;

    private float swipeDownX;
    private float swipeDownY;
    /** False once the gesture has disqualified itself — went vertical, or a second finger landed. */
    private boolean swipeCandidate;

    /**
     * Moves to the next or previous tab on a horizontal drag across the screen.
     *
     * <p>Watched at the activity rather than claimed by a view, and never consuming the event: the
     * gesture is read alongside whatever the screen underneath is already doing, and acts only on
     * release. That is what lets it share the horizontal axis with swipe-to-delete instead of
     * fighting it for interception — the row's gesture runs normally, and this one stands down
     * because {@link SwipeToDelete#isSwipeInProgress()} says a row has already claimed the drag.
     *
     * <p>The distance threshold is several times a row's, for the same reason. A row commits to
     * being dragged after about a finger's width; asking for a third of the screen here means that
     * by the time this gesture would qualify, any row that was going to claim it already has.
     *
     * <p>Only between the four top-level destinations. Deeper screens — a note, a review session, a
     * whiteboard — hide the bar entirely, and a horizontal drag on a whiteboard is a pen stroke.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeDownX = event.getX();
                swipeDownY = event.getY();
                swipeCandidate = true;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger means a pinch or a scroll, not a page turn.
                swipeCandidate = false;
                break;
            case MotionEvent.ACTION_UP:
                if (swipeCandidate) {
                    handleHorizontalSwipe(event.getX() - swipeDownX, event.getY() - swipeDownY);
                }
                swipeCandidate = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                swipeCandidate = false;
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    private void handleHorizontalSwipe(float dx, float dy) {
        // A row is mid-swipe, or was: the drag belongs to it either way, including the case where
        // it was released short of deleting and sprang back.
        if (SwipeToDelete.isSwipeInProgress()) return;

        float minDistance = getResources().getDisplayMetrics().widthPixels * SWIPE_TAB_FRACTION;
        if (Math.abs(dx) < minDistance) return;
        // Comfortably horizontal, so a diagonal flick while scrolling a list doesn't change tab.
        if (Math.abs(dx) < Math.abs(dy) * 2) return;

        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (host == null) return;
        NavController navController = host.getNavController();
        if (navController.getCurrentDestination() == null) return;

        int current = indexOfTab(navController.getCurrentDestination().getId());
        if (current < 0) return;

        // Swiping left carries the screen forward, the way a page does.
        int target = current + (dx < 0 ? 1 : -1);
        // No wrap-around: running off the end of the bar should feel like the end of the bar, not
        // like being thrown back to the start.
        if (target < 0 || target >= TAB_DESTINATIONS.length) return;

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        if (bottomNav == null || bottomNav.getVisibility() != View.VISIBLE) return;
        // Through the bar rather than the controller, so the selected item moves with the screen
        // and the back stack is popped exactly as a tap would have done it.
        bottomNav.setSelectedItemId(TAB_DESTINATIONS[target]);
    }

    private static int indexOfTab(int destinationId) {
        for (int i = 0; i < TAB_DESTINATIONS.length; i++) {
            if (TAB_DESTINATIONS[i] == destinationId) return i;
        }
        return -1;
    }

    private void setupBottomNavigation() {
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (host == null) return;

        NavController navController = host.getNavController();
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        NavigationUI.setupWithNavController(bottomNav, navController);

        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            boolean topLevel = destination.getId() == R.id.homeFragment
                    || destination.getId() == R.id.flashcardDecksFragment
                    || destination.getId() == R.id.quizzesFragment
                    || destination.getId() == R.id.profileFragment;
            bottomNav.setVisibility(topLevel ? View.VISIBLE : View.GONE);
            applyBottomInset();
        });
    }
}