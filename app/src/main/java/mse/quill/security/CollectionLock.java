package mse.quill.security;

import android.util.Log;

import androidx.fragment.app.FragmentActivity;

import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Which locked collections are open right now, and how one gets opened.
 *
 * <p>The persistent half of "locked" is {@code collections.biometric_locked} plus the Keystore key
 * behind it ({@link CollectionCrypto}); this class holds the other half — the in-memory record of
 * which of those the user has authenticated for during this run. Nothing here is written to disk,
 * deliberately: a collection that was open before the process died must be shut when it comes back.
 *
 * <p><b>Unlocking is for the session, not for the note.</b> One prompt opens a collection and it
 * stays open until the app is backgrounded or the process ends. Re-prompting per note would be
 * miserable in the only place this feature is used — reading through a collection — and would buy
 * nothing, because the Keystore key's own authentication window
 * ({@code CollectionCrypto.AUTH_VALIDITY_SECONDS}) is already what actually bounds decryption. The
 * flag here can only ever be more restrictive than the key, never less: if the key's window closes
 * first, the next read throws {@link CollectionCrypto.NeedsAuthException} and the flag is dropped.
 *
 * <p>Static, and for the same reason as {@link AppLock}: this is a property of the process, so a
 * configuration change must not re-prompt and a fresh process must.
 */
public final class CollectionLock {

    private static final String TAG = "CollectionLock";

    private static final Set<String> unlocked = Collections.synchronizedSet(new HashSet<>());

    private CollectionLock() {}

    /**
     * Whether this collection's contents may be read right now. A null id — a note filed nowhere —
     * is always readable; there is no collection to have locked it.
     *
     * <p>Callers pair this with the {@code biometric_locked} column: unlocked-in-session only
     * means anything for a collection that is locked in the first place.
     */
    public static boolean isUnlocked(String collectionId) {
        return collectionId == null || unlocked.contains(collectionId);
    }

    public static void markUnlocked(String collectionId) {
        if (collectionId != null) unlocked.add(collectionId);
    }

    /** Shuts one collection — used when its key turns out to be unusable, and when the user
     *  removes the lock (at which point the flag is meaningless and shouldn't linger). */
    public static void relock(String collectionId) {
        unlocked.remove(collectionId);
    }

    /**
     * Shuts everything. Called when the app stops being visible, so returning to a collection
     * always costs a prompt rather than depending on how long the phone was in a pocket.
     */
    public static void relockAll() {
        unlocked.clear();
    }

    /** Outcome of {@link #unlock}. */
    public interface Listener {
        void onUnlocked();

        /** @param userCancelled true if the user dismissed the prompt rather than it failing. */
        void onFailed(boolean userCancelled, CharSequence message);

        /**
         * The key is gone — a removed screen lock, or a cleared keystore. The collection's notes
         * cannot be decrypted by anything, so the only honest options are to keep the ciphertext
         * or discard it; the caller is what asks.
         */
        void onKeyGone();
    }

    /**
     * Prompts, then proves the authentication actually reached the key before declaring success.
     *
     * <p>The proof is the point. {@code BiometricPrompt} reporting success says the user
     * authenticated, not that this key is usable — those come apart when the key has been
     * invalidated, and without the check the collection would open, look normal, and then fail on
     * the first note the user tapped.
     */
    public static void unlock(FragmentActivity activity, String collectionId, Listener listener) {
        AppLock.authenticate(activity, new AppLock.Listener() {
            @Override public void onUnlocked() {
                try {
                    CollectionCrypto.assertUsable(collectionId);
                    markUnlocked(collectionId);
                    listener.onUnlocked();
                } catch (CollectionCrypto.KeyGoneException e) {
                    Log.w(TAG, "collection key unusable after auth", e);
                    relock(collectionId);
                    listener.onKeyGone();
                } catch (GeneralSecurityException e) {
                    // Includes NeedsAuthException, which here means the authentication that just
                    // succeeded didn't satisfy the key — a real mismatch, not a cancellation.
                    Log.w(TAG, "collection key rejected the authentication", e);
                    relock(collectionId);
                    listener.onFailed(false, e.getMessage());
                }
            }

            @Override public void onFailed(int errorCode, CharSequence message) {
                listener.onFailed(AppLock.isUserCancellation(errorCode), message);
            }
        });
    }
}
