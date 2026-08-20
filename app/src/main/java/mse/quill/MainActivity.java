package mse.quill;

import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

import mse.quill.collab.SessionCode;
import mse.quill.data.AppExecutors;
import mse.quill.data.WearNoteListPublisher;
import mse.quill.data.WearProjectionPublisher;
import mse.quill.data.WearReadStatePublisher;
import mse.quill.reminders.StudyReminders;
import mse.quill.security.AppLock;
import mse.quill.security.CollectionLock;
import mse.quill.ui.audio.MiniPlayerView;
import mse.quill.util.WindowInsetsUtils;

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

        // Before handleViewIntent below, which is the thing that reads them.
        if (savedInstanceState != null) {
            pendingImportUri = savedInstanceState.getParcelable(STATE_PENDING_IMPORT);
            viewIntentConsumed = savedInstanceState.getBoolean(STATE_IMPORT_CONSUMED, false);
            pendingJoinToken = savedInstanceState.getString(STATE_PENDING_JOIN);
        }

        applyTopInsetToEveryScreen();
        setupNowPlayingBar();
        setupBottomNavigation();
        setupAppLock();
        deliverSharedFileWhenHomeIsReady();
        handleViewIntent(getIntent());
        handleReminderIntent(getIntent());
        handleWidgetIntent(getIntent());

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

    /** Extra on the reminder notification's intent: land on the Flashcards tab. */
    public static final String EXTRA_OPEN_FLASHCARDS = "open_flashcards";

    /**
     * Sends the user to the decks when they tap the study reminder.
     *
     * <p>Goes through the bottom bar's own destination rather than a bare {@code navigate}, so the
     * tab comes up selected and the back stack looks the way it would if they had tapped it
     * themselves — arriving on Flashcards with Home highlighted is the sort of thing that makes an
     * app feel like it was assembled from two halves.
     */
    private void handleReminderIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_OPEN_FLASHCARDS, false)) return;
        // Consumed, or a configuration change would re-deliver the same intent and yank the user
        // back to Flashcards from wherever they had since navigated.
        intent.removeExtra(EXTRA_OPEN_FLASHCARDS);

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setSelectedItemId(R.id.flashcardDecksFragment);
    }

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
        // A new intent is a new request, whatever the last one was — see viewIntentConsumed.
        viewIntentConsumed = false;
        handleViewIntent(intent);
        handleReminderIntent(intent);
        handleWidgetIntent(intent);
    }

    /** Extras a home-screen widget tap arrives with — see {@code mse.quill.widget}. Exactly one is
     *  ever set on a given intent. */
    public static final String EXTRA_OPEN_NOTE_ID = "widget_open_note_id";
    public static final String EXTRA_OPEN_COLLECTION_ID = "widget_open_collection_id";
    public static final String EXTRA_OPEN_COLLECTION_NAME = "widget_open_collection_name";
    public static final String EXTRA_OPEN_WHITEBOARD_ID = "widget_open_whiteboard_id";
    /** Separate from {@link #EXTRA_OPEN_NOTE_ID}: that one opens the note editor, this one opens
     *  the note's flashcard review screen — same note id, different destination. */
    public static final String EXTRA_OPEN_FLASHCARD_NOTE_ID = "widget_open_flashcard_note_id";

    /**
     * Sends the user straight to whatever they tapped in a widget — a pinned note, a collection,
     * or a whiteboard — rather than dropping them on Home to find it themselves.
     *
     * <p>Follows {@link #deliverSharedFileWhenHomeIsReady}'s shape: a cold start may not have
     * inflated the nav host yet, so this waits for Home to be resumed before navigating, the same
     * way a shared file's import waits.
     */
    private void handleWidgetIntent(Intent intent) {
        if (intent == null) return;
        String noteId = intent.getStringExtra(EXTRA_OPEN_NOTE_ID);
        String collectionId = intent.getStringExtra(EXTRA_OPEN_COLLECTION_ID);
        String whiteboardId = intent.getStringExtra(EXTRA_OPEN_WHITEBOARD_ID);
        String flashcardNoteId = intent.getStringExtra(EXTRA_OPEN_FLASHCARD_NOTE_ID);
        if (noteId == null && collectionId == null && whiteboardId == null
                && flashcardNoteId == null) return;

        intent.removeExtra(EXTRA_OPEN_NOTE_ID);
        intent.removeExtra(EXTRA_OPEN_COLLECTION_ID);
        intent.removeExtra(EXTRA_OPEN_COLLECTION_NAME);
        intent.removeExtra(EXTRA_OPEN_WHITEBOARD_ID);
        intent.removeExtra(EXTRA_OPEN_FLASHCARD_NOTE_ID);
        String collectionName = intent.getStringExtra(EXTRA_OPEN_COLLECTION_NAME);

        runWhenNavHostReady(host -> {
            NavController nav = host.getNavController();
            Bundle args = new Bundle();
            if (noteId != null) {
                args.putString("note_id", noteId);
                nav.navigate(R.id.noteEditorFragment, args);
            } else if (collectionId != null) {
                args.putString("collection_id", collectionId);
                args.putString("collection_name", collectionName == null ? "" : collectionName);
                nav.navigate(R.id.collectionDetailFragment, args);
            } else if (whiteboardId != null) {
                args.putString("whiteboard_id", whiteboardId);
                nav.navigate(R.id.whiteboardFragment, args);
            } else {
                args.putString("note_id", flashcardNoteId);
                nav.navigate(R.id.flashcardsFragment, args);
            }
        });
    }

    /** Runs {@code action} once the nav host exists and Home is its resumed fragment — the point
     *  {@link #deliverPendingImportIfReady} already waits for, reused here so a widget tap arriving
     *  before Home has inflated still lands correctly instead of silently doing nothing. */
    private void runWhenNavHostReady(java.util.function.Consumer<NavHostFragment> action) {
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);

        // A widget tap has to land on the item it named regardless of where the user left the
        // app — mid-note, on a quiz, anywhere. Without this, waiting below for Home to become the
        // resumed fragment would wait forever: nothing else drives the back stack there. Mirrors
        // handleViewIntent's own popBackStack call for the same reason.
        if (host != null) host.getNavController().popBackStack(R.id.homeFragment, false);

        Fragment current = host == null ? null
                : host.getChildFragmentManager().getPrimaryNavigationFragment();
        if (host != null && current instanceof mse.quill.ui.home.HomeFragment
                && current.isResumed()) {
            action.accept(host);
            return;
        }
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment fragment) {
                        if (!(fragment instanceof mse.quill.ui.home.HomeFragment)) return;
                        fm.unregisterFragmentLifecycleCallbacks(this);
                        NavHostFragment readyHost = (NavHostFragment) getSupportFragmentManager()
                                .findFragmentById(R.id.nav_host_fragment);
                        if (readyHost != null) action.accept(readyHost);
                    }
                }, true);
    }

    /** Set once a VIEW intent names a file, and cleared once {@code HomeFragment} has it — Home may
     *  not be the resumed fragment yet (a cold start still has to inflate the nav host), and this is
     *  what lets {@link #deliverSharedFileWhenHomeIsReady} deliver it the moment it is. */
    private Uri pendingImportUri;

    /**
     * Whether the file named by the <em>current</em> intent has already been handed to Home.
     *
     * <p>The activity's intent outlives the activity: a rotation recreates this Activity and
     * {@link #getIntent()} still returns the VIEW intent that started it, so without this the file
     * would be imported a second time and the user would find two copies of the note they opened
     * once. Reset in {@link #onNewIntent}, because a new intent is a new request — the same file
     * tapped twice on purpose is two imports, and that is the user's call to make.
     */
    private boolean viewIntentConsumed;

    private static final String STATE_PENDING_IMPORT = "pending_import_uri";
    private static final String STATE_IMPORT_CONSUMED = "view_intent_consumed";
    private static final String STATE_PENDING_JOIN = "pending_join_token";

    /** Carries both across a rotation: the flag so an imported file isn't imported again, and the
     *  uri so one that arrived in the moment before Home was ready isn't dropped instead. */
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(STATE_PENDING_IMPORT, pendingImportUri);
        outState.putBoolean(STATE_IMPORT_CONSUMED, viewIntentConsumed);
        outState.putString(STATE_PENDING_JOIN, pendingJoinToken);
    }

    private void handleViewIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri uri = intent.getData();
        if (uri == null || viewIntentConsumed) return;
        viewIntentConsumed = true;

        // Two kinds of thing arrive as a VIEW: a file to import, and a whiteboard session's link.
        // The scheme settles it before either is attempted — a quill:// link handed to the
        // importers would be opened, found to contain no bundle, and reported as a broken file.
        if ("quill".equals(uri.getScheme())) {
            String token = SessionCode.parse(uri.getLastPathSegment());
            if (token == null) {
                android.widget.Toast.makeText(this, R.string.collab_error_not_a_session,
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            joinSessionWhenUnlocked(token);
            return;
        }

        pendingImportUri = uri;

        // The file's result belongs on Home (that's where a manually-picked import already lands),
        // so a VIEW intent arriving while the user is elsewhere in the app — mid-note, on a quiz —
        // has to surface there first. A no-op if Home is already the top of the back stack.
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (host != null) host.getNavController().popBackStack(R.id.homeFragment, false);

        deliverPendingImportIfReady();
    }

    /**
     * Opens a board joined to the scanned session — but not until the app is unlocked.
     *
     * <p>The lock gate is a view over this window rather than a screen of its own, so Home goes on
     * resuming behind it: without the wait, a link scanned while Quill was locked would create a
     * board, join a stranger's session and start drawing it onto the screen underneath the words
     * "Quill is locked". The gate has to come down first, and if the user walks away from it,
     * nothing has happened at all.
     */
    private void joinSessionWhenUnlocked(String token) {
        if (AppLock.shouldPrompt(this)) {
            pendingJoinToken = token;
            return;
        }
        runWhenNavHostReady(host -> {
            Bundle args = new Bundle();
            args.putBoolean(mse.quill.ui.whiteboard.WhiteboardFragment.ARG_CREATED_NOW, true);
            args.putString(mse.quill.ui.whiteboard.WhiteboardFragment.ARG_JOIN_TOKEN, token);
            // No whiteboard_id: the screen mints one for itself, which is exactly what a joiner
            // needs — an empty board of its own for the host's snapshot to fill.
            host.getNavController().navigate(R.id.whiteboardFragment, args);
        });
    }

    /** A session link that arrived while the gate was up, waiting for it to come down. */
    private String pendingJoinToken;

    /** Home is resumed as soon as it exists, cold start or not, so watching for that (rather than
     *  e.g. a fixed delay) is what makes this reliable regardless of how long the nav host takes to
     *  inflate it. */
    private void deliverSharedFileWhenHomeIsReady() {
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment fragment) {
                        if (fragment instanceof mse.quill.ui.home.HomeFragment) {
                            deliverPendingImportIfReady();
                        }
                    }
                }, true);
    }

    private void deliverPendingImportIfReady() {
        if (pendingImportUri == null) return;
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (host == null) return;
        Fragment current = host.getChildFragmentManager().getPrimaryNavigationFragment();
        if (!(current instanceof mse.quill.ui.home.HomeFragment)) return;

        // Resumed, not merely present — the check this used to be missing, and the difference
        // between the two ways a file can arrive. Tapping a .quill in a file manager while Quill is
        // already running finds a Home with a view, so it worked; doing it with Quill closed does
        // not. A cold start restores the fragment during onCreate, so the instanceof above already
        // passes while onCreateView is still ahead of it — and Home's first act on being handed a
        // file is to show a Snackbar, which needs the view it does not yet have. That threw
        // IllegalStateException out of onCreate, which is to say Quill crashed on launch and the
        // user was dropped back in the file manager with nothing imported.
        //
        // Nothing is lost by waiting: deliverSharedFileWhenHomeIsReady is registered before the
        // intent is ever read, and calls back here the moment Home resumes.
        if (!current.isResumed()) return;

        Uri uri = pendingImportUri;
        pendingImportUri = null;
        ((mse.quill.ui.home.HomeFragment) current).handleSharedFile(uri);
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
            deliverPendingJoin();
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
        if (AppLock.isEnabled(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
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
        deliverPendingJoin();
    }

    /** The other half of {@link #joinSessionWhenUnlocked}: the gate is down, so the session link
     *  that was waiting behind it can go ahead. */
    private void deliverPendingJoin() {
        if (pendingJoinToken == null) return;
        String token = pendingJoinToken;
        pendingJoinToken = null;
        joinSessionWhenUnlocked(token);
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