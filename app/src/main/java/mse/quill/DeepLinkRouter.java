package mse.quill;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.function.Consumer;

import mse.quill.collab.SessionCode;
import mse.quill.security.AppLock;
import mse.quill.ui.home.HomeFragment;
import mse.quill.ui.whiteboard.WhiteboardFragment;

/**
 * Everything that arrives from outside the app and names a place to go: a widget tap, the study
 * reminder's notification, a {@code .quill} file opened from a file manager, and a
 * {@code quill://} collaboration link.
 *
 * <p>Split out of {@code MainActivity}, which is 830 lines and mostly doing genuinely
 * Activity-shaped work — insets, the app-lock gate, the now-playing bar, swipe and bottom
 * navigation. Intent routing is the part that is not: it is a protocol, with its own vocabulary of
 * extras and its own awkward state, and it was interleaved with all of that.
 *
 * <p><b>The state is the reason this is a class and not three static methods.</b> Three things
 * have to survive a rotation and be handed on at the right moment:
 *
 * <ul>
 *   <li>{@link #pendingImportUri} — a file named before Home was ready to receive it;
 *   <li>{@link #viewIntentConsumed} — whether the current intent's file has already been handed
 *       over, since the activity's intent outlives the activity;
 *   <li>{@link #pendingJoinToken} — a session link that arrived while the lock gate was up.
 * </ul>
 *
 * <p>The activity drives it through five calls: {@link #onCreate}, {@link #route},
 * {@link #onNewIntent}, {@link #onSaveInstanceState} and {@link #onUnlocked}.
 */
public final class DeepLinkRouter {

    /** Extra on the reminder notification's intent: land on the Flashcards tab. */
    public static final String EXTRA_OPEN_FLASHCARDS = "open_flashcards";

    /** Extras a home-screen widget tap arrives with — see {@code mse.quill.widget}. Exactly one is
     *  ever set on a given intent. */
    public static final String EXTRA_OPEN_NOTE_ID = "widget_open_note_id";
    public static final String EXTRA_OPEN_COLLECTION_ID = "widget_open_collection_id";
    public static final String EXTRA_OPEN_COLLECTION_NAME = "widget_open_collection_name";
    public static final String EXTRA_OPEN_WHITEBOARD_ID = "widget_open_whiteboard_id";
    /** Separate from {@link #EXTRA_OPEN_NOTE_ID}: that one opens the note editor, this one opens
     *  the note's flashcard review screen — same note id, different destination. */
    public static final String EXTRA_OPEN_FLASHCARD_NOTE_ID = "widget_open_flashcard_note_id";

    private static final String STATE_PENDING_IMPORT = "pending_import_uri";
    private static final String STATE_IMPORT_CONSUMED = "view_intent_consumed";
    private static final String STATE_PENDING_JOIN = "pending_join_token";

    private final FragmentActivity activity;

    /** Set once a VIEW intent names a file, and cleared once {@code HomeFragment} has it — Home may
     *  not be the resumed fragment yet (a cold start still has to inflate the nav host), and this is
     *  what lets {@link #deliverSharedFileWhenHomeIsReady} deliver it the moment it is. */
    private Uri pendingImportUri;

    /**
     * Whether the file named by the <em>current</em> intent has already been handed to Home.
     *
     * <p>The activity's intent outlives the activity: a rotation recreates it and
     * {@code getIntent()} still returns the VIEW intent that started it, so without this the file
     * would be imported a second time and the user would find two copies of the note they opened
     * once. Reset in {@link #onNewIntent}, because a new intent is a new request — the same file
     * tapped twice on purpose is two imports, and that is the user's call to make.
     */
    private boolean viewIntentConsumed;

    /** A session link that arrived while the gate was up, waiting for it to come down. */
    private String pendingJoinToken;

    DeepLinkRouter(FragmentActivity activity) {
        this.activity = activity;
    }

    // ── Activity lifecycle ────────────────────────────────────────────────────

    /** Restores the pending state and starts watching for Home, both before any intent is read. */
    void onCreate(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            pendingImportUri = savedInstanceState.getParcelable(STATE_PENDING_IMPORT);
            viewIntentConsumed = savedInstanceState.getBoolean(STATE_IMPORT_CONSUMED, false);
            pendingJoinToken = savedInstanceState.getString(STATE_PENDING_JOIN);
        }
        deliverSharedFileWhenHomeIsReady();
    }

    /** Carries all three across a rotation: the flag so an imported file isn't imported again, the
     *  uri so one that arrived in the moment before Home was ready isn't dropped instead, and the
     *  token so a session link doesn't die behind the lock gate. */
    void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putParcelable(STATE_PENDING_IMPORT, pendingImportUri);
        outState.putBoolean(STATE_IMPORT_CONSUMED, viewIntentConsumed);
        outState.putString(STATE_PENDING_JOIN, pendingJoinToken);
    }

    /** Reads everything routable off one intent. Order matters only in that the VIEW handler may
     *  pop the back stack, which the other two then navigate from. */
    void route(Intent intent) {
        handleViewIntent(intent);
        handleReminderIntent(intent);
        handleWidgetIntent(intent);
    }

    /** A new intent is a new request, whatever the last one was — see {@link #viewIntentConsumed}. */
    void onNewIntent(Intent intent) {
        viewIntentConsumed = false;
        route(intent);
    }

    /** The other half of {@link #joinSessionWhenUnlocked}: the gate is down, so the session link
     *  that was waiting behind it can go ahead. */
    void onUnlocked() {
        if (pendingJoinToken == null) return;
        String token = pendingJoinToken;
        pendingJoinToken = null;
        joinSessionWhenUnlocked(token);
    }

    // ── The reminder ──────────────────────────────────────────────────────────

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

        BottomNavigationView bottomNav = activity.findViewById(R.id.bottom_nav);
        bottomNav.setSelectedItemId(R.id.flashcardDecksFragment);
    }

    // ── A widget tap ──────────────────────────────────────────────────────────

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

    // ── A file, or a session link ─────────────────────────────────────────────

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
                Toast.makeText(activity, R.string.collab_error_not_a_session,
                        Toast.LENGTH_LONG).show();
                return;
            }
            joinSessionWhenUnlocked(token);
            return;
        }

        pendingImportUri = uri;

        // The file's result belongs on Home (that's where a manually-picked import already lands),
        // so a VIEW intent arriving while the user is elsewhere in the app — mid-note, on a quiz —
        // has to surface there first. A no-op if Home is already the top of the back stack.
        NavHostFragment host = navHost();
        if (host != null) host.getNavController().popBackStack(R.id.homeFragment, false);

        deliverPendingImportIfReady();
    }

    /**
     * Opens a board joined to the scanned session — but not until the app is unlocked.
     *
     * <p>The lock gate is a view over the activity's window rather than a screen of its own, so
     * Home goes on resuming behind it: without the wait, a link scanned while Quill was locked
     * would create a board, join a stranger's session and start drawing it onto the screen
     * underneath the words "Quill is locked". The gate has to come down first, and if the user
     * walks away from it, nothing has happened at all.
     */
    private void joinSessionWhenUnlocked(String token) {
        if (AppLock.shouldPrompt(activity)) {
            pendingJoinToken = token;
            return;
        }
        runWhenNavHostReady(host -> {
            Bundle args = new Bundle();
            args.putBoolean(WhiteboardFragment.ARG_CREATED_NOW, true);
            args.putString(WhiteboardFragment.ARG_JOIN_TOKEN, token);
            // No whiteboard_id: the screen mints one for itself, which is exactly what a joiner
            // needs — an empty board of its own for the host's snapshot to fill.
            host.getNavController().navigate(R.id.whiteboardFragment, args);
        });
    }

    /** Home is resumed as soon as it exists, cold start or not, so watching for that (rather than
     *  e.g. a fixed delay) is what makes this reliable regardless of how long the nav host takes to
     *  inflate it. */
    private void deliverSharedFileWhenHomeIsReady() {
        activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment fragment) {
                        if (fragment instanceof HomeFragment) {
                            deliverPendingImportIfReady();
                        }
                    }
                }, true);
    }

    private void deliverPendingImportIfReady() {
        if (pendingImportUri == null) return;
        NavHostFragment host = navHost();
        if (host == null) return;
        Fragment current = host.getChildFragmentManager().getPrimaryNavigationFragment();
        if (!(current instanceof HomeFragment)) return;

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
        ((HomeFragment) current).handleSharedFile(uri);
    }

    // ── Shared plumbing ───────────────────────────────────────────────────────

    private NavHostFragment navHost() {
        return (NavHostFragment) activity.getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
    }

    /** Runs {@code action} once the nav host exists and Home is its resumed fragment — the point
     *  {@link #deliverPendingImportIfReady} already waits for, reused here so a widget tap arriving
     *  before Home has inflated still lands correctly instead of silently doing nothing. */
    private void runWhenNavHostReady(Consumer<NavHostFragment> action) {
        NavHostFragment host = navHost();

        // A widget tap has to land on the item it named regardless of where the user left the
        // app — mid-note, on a quiz, anywhere. Without this, waiting below for Home to become the
        // resumed fragment would wait forever: nothing else drives the back stack there. Mirrors
        // handleViewIntent's own popBackStack call for the same reason.
        if (host != null) host.getNavController().popBackStack(R.id.homeFragment, false);

        Fragment current = host == null ? null
                : host.getChildFragmentManager().getPrimaryNavigationFragment();
        if (host != null && current instanceof HomeFragment && current.isResumed()) {
            action.accept(host);
            return;
        }
        activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment fragment) {
                        if (!(fragment instanceof HomeFragment)) return;
                        fm.unregisterFragmentLifecycleCallbacks(this);
                        NavHostFragment readyHost = navHost();
                        if (readyHost != null) action.accept(readyHost);
                    }
                }, true);
    }
}
