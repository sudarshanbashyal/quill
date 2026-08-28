package mse.quill.ui.common;

import android.view.View;

import com.google.android.material.snackbar.Snackbar;

import java.util.HashSet;
import java.util.Set;

import mse.quill.R;

/**
 * Deferred deletion: the row leaves the list at once, the database is not touched until the user
 * has had a few seconds to say no.
 *
 * <p>Deferring is what makes undo possible at all here. A note is soft-deleted and could in
 * principle be restored, but a deck, a quiz and a whiteboard are removed outright — a quiz takes
 * its attempt history with it and a board takes every stroke — so "delete, then put it back" would
 * mean capturing and replaying all of that. Not writing yet is the same promise with none of the
 * bookkeeping, and it fails in the safe direction: if the process dies mid-window, the delete
 * simply never happened.
 *
 * <p>The Snackbar is the timer. Its dismissal — whether it timed out, was swiped away, or was
 * replaced by the next one — is what commits, so there is no second clock to keep in step with the
 * bar the user is actually looking at. Leaving the screen detaches the bar, which dismisses it,
 * which commits: backing out of a list is not a way to smuggle a deletion back.
 *
 * <p>Commit callbacks must therefore be safe to run while their screen is being torn down. In
 * practice they call a repository, which is bound to the application context and its own executors,
 * not to the view that has gone.
 */
public final class UndoDelete {

    /**
     * The keys currently hidden. Static because the hiding outlives the fragment instance — a
     * rotation rebuilds the list from the database, which still holds the row, and without this
     * the item would reappear underneath its own undo bar.
     */
    private static final Set<String> hidden = new HashSet<>();

    private UndoDelete() {}

    /** Whether this id has been deleted as far as the user can tell, and so must not be listed. */
    public static boolean isHidden(String key) {
        return hidden.contains(key);
    }

    /**
     * Hides {@code key}, tells the user, and runs exactly one of {@code onUndo} / {@code onCommit}.
     *
     * @param anchor  any view in the screen the bar belongs to; its detachment also commits
     * @param onUndo  re-render the list — the row was never actually removed from storage
     * @param onCommit the real deletion
     */
    public static void offer(View anchor, CharSequence message, String key,
                             Runnable onUndo, Runnable onCommit) {
        hidden.add(key);

        // Guards against the callback pair running twice: tapping Undo also dismisses the bar, so
        // onDismissed arrives after the action has already been handled.
        boolean[] undone = {false};

        Snackbar bar = Snackbar.make(anchor, message, Snackbar.LENGTH_LONG);
        // Above the bottom bar rather than across it. Undo sits at the same end of the bar as the
        // last tab, so an overlapping snackbar means a tap aimed at Undo a moment too late lands on
        // Profile instead — which both misses the undo and leaves the screen.
        View bottomNav = anchor.getRootView().findViewById(R.id.bottom_nav);
        if (bottomNav != null && bottomNav.getVisibility() == View.VISIBLE) {
            bar.setAnchorView(bottomNav);
        }
        bar.setAction(R.string.action_undo, v -> {
            undone[0] = true;
            hidden.remove(key);
            onUndo.run();
        });
        bar.addCallback(new Snackbar.Callback() {
            @Override
            public void onDismissed(Snackbar snackbar, int event) {
                if (undone[0]) return;
                hidden.remove(key);
                onCommit.run();
            }
        });
        bar.show();
    }
}
