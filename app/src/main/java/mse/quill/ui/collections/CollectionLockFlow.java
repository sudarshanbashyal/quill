package mse.quill.ui.collections;

import android.content.Context;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import mse.quill.R;
import mse.quill.data.CollectionLockRepository;
import mse.quill.data.model.Collection;
import mse.quill.security.AppLock;
import mse.quill.security.CollectionLock;

/**
 * The three things a user can do with a collection's lock — open it, turn it on, turn it off —
 * with the dialogs and authentication each needs.
 *
 * <p>Lives here rather than in either screen because both Home and {@link CollectionDetailFragment}
 * offer all three, and the wording is the part that must not drift: these dialogs are where the
 * user is told what locking costs and what unlocking gives away, and two copies of that would
 * eventually disagree.
 */
public final class CollectionLockFlow {

    public interface Done { void onDone(); }

    private CollectionLockFlow() {}

    /**
     * Opens a locked collection for this session, or runs {@code onOpen} straight away if it isn't
     * locked. Every route into a collection's contents goes through here.
     */
    public static void openCollection(FragmentActivity activity, Collection collection, Done onOpen) {
        if (!collection.biometricLocked || CollectionLock.isUnlocked(collection.id)) {
            onOpen.onDone();
            return;
        }
        promptUnlock(activity, collection, onOpen);
    }

    /** Same, for a caller that has only the id and the locked flag to hand. */
    public static void openCollection(FragmentActivity activity, String collectionId,
                                      String name, boolean locked, Done onOpen) {
        Collection collection = new Collection();
        collection.id = collectionId;
        collection.name = name;
        collection.biometricLocked = locked;
        openCollection(activity, collection, onOpen);
    }

    private static void promptUnlock(FragmentActivity activity, Collection collection, Done onOpen) {
        CollectionLock.unlock(activity, collection.id, new CollectionLock.Listener() {
            @Override public void onUnlocked() {
                onOpen.onDone();
            }

            @Override public void onFailed(boolean userCancelled, CharSequence message) {
                // Backing out of the prompt is a decision, not an error; the collection simply
                // stays shut and the user is left where they were.
                if (!userCancelled) {
                    Toast.makeText(activity, R.string.collection_unlock_failed,
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override public void onKeyGone() {
                showKeyGoneDialog(activity, collection);
            }
        });
    }

    /**
     * What to do about a collection whose key is gone — a removed screen lock, most often, which
     * destroys every key that was bound to it.
     *
     * <p>Presented as a choice because there is genuinely nothing else to offer: the notes cannot
     * be decrypted by Quill, by the user, or by anyone with the device. Deleting them is the only
     * action available, and doing that automatically would mean an app that quietly ate a
     * collection because someone changed their PIN settings. So it says exactly what happened and
     * lets the ciphertext sit there until the user is ready.
     */
    private static void showKeyGoneDialog(FragmentActivity activity, Collection collection) {
        CollectionLockRepository repository = new CollectionLockRepository(activity);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.collection_key_gone_title)
                .setMessage(R.string.collection_key_gone_message)
                .setNegativeButton(R.string.collection_key_gone_keep, null)
                .setPositiveButton(R.string.collection_key_gone_discard, (d, w) ->
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.collection_key_gone_confirm_title)
                                .setMessage(R.string.collection_key_gone_confirm_message)
                                .setNegativeButton(R.string.action_cancel, null)
                                .setPositiveButton(R.string.action_delete, (d2, w2) ->
                                        repository.discardUnreadable(collection.id, () ->
                                                Toast.makeText(activity,
                                                        R.string.collection_key_gone_discarded,
                                                        Toast.LENGTH_SHORT).show()))
                                .show())
                .show();
    }

    /**
     * Turns the lock on: explain, authenticate, then encrypt.
     *
     * <p>Authentication comes before the work rather than after it because it is not a confirmation
     * step — it is what opens the Keystore key's window, so the encryption has something to run
     * with. A user who can't authenticate can't lock the collection, which is the correct outcome:
     * they wouldn't be able to open it afterwards either.
     */
    public static void lock(FragmentActivity activity, Collection collection, Done onDone) {
        if (!AppLock.isAvailable(activity)) {
            Toast.makeText(activity, R.string.collection_lock_unavailable, Toast.LENGTH_LONG).show();
            return;
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.collection_lock_title_format, collection.name))
                .setMessage(R.string.collection_lock_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.collection_lock_confirm, (d, w) ->
                        AppLock.authenticate(activity, new AppLock.Listener() {
                            @Override public void onUnlocked() {
                                runLock(activity, collection, onDone);
                            }

                            @Override public void onFailed(int code, CharSequence message) {
                                if (!AppLock.isUserCancellation(code)) {
                                    Toast.makeText(activity, R.string.collection_lock_failed,
                                            Toast.LENGTH_LONG).show();
                                }
                            }
                        }))
                .show();
    }

    private static void runLock(FragmentActivity activity, Collection collection, Done onDone) {
        Toast.makeText(activity, R.string.collection_locking, Toast.LENGTH_SHORT).show();
        new CollectionLockRepository(activity).lock(collection.id, new CollectionLockRepository.Callback() {
            @Override public void onDone() {
                Toast.makeText(activity, R.string.collection_locked, Toast.LENGTH_SHORT).show();
                onDone.onDone();
            }

            @Override public void onFailed(boolean needsAuth) {
                Toast.makeText(activity, R.string.collection_lock_failed, Toast.LENGTH_LONG).show();
                onDone.onDone();
            }
        });
    }

    /**
     * Turns the lock off for good, decrypting everything back to plaintext.
     *
     * <p>Confirmed as well as authenticated, and worded as the removal of protection rather than
     * as "unlock" — the session-level unlock above uses that word for something much smaller, and
     * confusing the two would be the expensive kind of mistake.
     */
    public static void removeLock(FragmentActivity activity, Collection collection, Done onDone) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.collection_unlock_title_format, collection.name))
                .setMessage(R.string.collection_unlock_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.collection_unlock_confirm, (d, w) ->
                        AppLock.authenticate(activity, new AppLock.Listener() {
                            @Override public void onUnlocked() {
                                runRemoveLock(activity, collection, onDone);
                            }

                            @Override public void onFailed(int code, CharSequence message) {
                                if (!AppLock.isUserCancellation(code)) {
                                    Toast.makeText(activity, R.string.collection_unlock_failed,
                                            Toast.LENGTH_LONG).show();
                                }
                            }
                        }))
                .show();
    }

    private static void runRemoveLock(FragmentActivity activity, Collection collection, Done onDone) {
        new CollectionLockRepository(activity).unlock(collection.id, new CollectionLockRepository.Callback() {
            @Override public void onDone() {
                Toast.makeText(activity, R.string.collection_unlocked, Toast.LENGTH_SHORT).show();
                onDone.onDone();
            }

            @Override public void onFailed(boolean needsAuth) {
                Toast.makeText(activity, needsAuth
                        ? R.string.collection_unlock_failed
                        : R.string.collection_unlock_key_gone, Toast.LENGTH_LONG).show();
                onDone.onDone();
            }
        });
    }

    /** The manage-dialog label, which flips with the collection's state. */
    public static String toggleLabel(Context context, Collection collection) {
        return context.getString(collection.biometricLocked
                ? R.string.action_remove_lock : R.string.action_lock_collection);
    }
}
