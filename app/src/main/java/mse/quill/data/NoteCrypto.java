package mse.quill.data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.Set;

import mse.quill.security.CollectionCrypto;
import mse.quill.security.CollectionLock;

/**
 * Where the notes table meets {@link CollectionCrypto}: which collections are readable, and how a
 * note's title and body cross between plaintext and ciphertext.
 *
 * <p><b>There is no marker byte.</b> Whether a row is encrypted is decided entirely by its
 * collection's {@code biometric_locked} column, which is also what the lock and unlock migrations
 * flip — inside the same transaction that rewrites the rows. Storing a per-row "encrypted" flag
 * would be a second source of truth able to disagree with the first, and a flag an attacker can
 * clear is worse than no flag at all. The cost is that the column and the rows must never be
 * updated apart, which is why {@code CollectionLockRepository} does both in one transaction.
 *
 * <p>The title lives in a {@code TEXT} column, so its ciphertext is Base64'd; the body is already
 * a {@code BLOB} and is stored raw.
 *
 * <p>Every method here runs on the disk thread — {@link AppExecutors#diskIO}. None of them may be
 * called from the main thread.
 */
final class NoteCrypto {

    private static final String TAG = "NoteCrypto";

    private NoteCrypto() {}

    // ---------- Lock state ----------

    /** True if this collection is locked at rest. Says nothing about whether it is open now. */
    static boolean isLocked(SQLiteDatabase db, String collectionId) {
        if (collectionId == null) return false;
        try (Cursor c = db.rawQuery("SELECT biometric_locked FROM collections WHERE id = ?",
                new String[]{collectionId})) {
            return c.moveToFirst() && c.getInt(0) != 0;
        }
    }

    /**
     * The collections whose notes must not appear anywhere right now: locked at rest, and not
     * opened in this session.
     *
     * <p>This is the set that keeps a locked collection out of Home, search, the flashcard decks
     * and the quiz list. Gating only the collection screen would have left every one of those
     * showing the contents of a collection the user believes is shut — the previews on Home are
     * literally the first line of the note.
     */
    static Set<String> hiddenCollectionIds(SQLiteDatabase db) {
        return hiddenOf(lockedCollectionIds(db));
    }

    /**
     * The same set as {@link #hiddenCollectionIds}, for a caller that already needed
     * {@link #lockedCollectionIds} for its own reasons.
     *
     * <p>A list query needs both halves of the distinction, not just one: <em>hidden</em> says which
     * rows to leave out, but <em>locked</em> says which of the rows that survive are still ciphertext
     * on disk and have to be decrypted on the way out. Asking only which collections are hidden and
     * then rendering what the cursor returns is how an open collection's titles reach the screen as
     * Base64.
     */
    static Set<String> hiddenOf(Set<String> lockedIds) {
        Set<String> hidden = new HashSet<>();
        for (String id : lockedIds) {
            if (!CollectionLock.isUnlocked(id)) hidden.add(id);
        }
        return hidden;
    }

    /** Every collection encrypted at rest, open or not. Read once per query rather than asked
     *  per note — {@link #isLocked} is a round trip, and a list is a loop. */
    static Set<String> lockedCollectionIds(SQLiteDatabase db) {
        Set<String> locked = new HashSet<>();
        try (Cursor c = db.rawQuery(
                "SELECT id FROM collections WHERE biometric_locked = 1", null)) {
            while (c.moveToNext()) locked.add(c.getString(0));
        }
        return locked;
    }

    /**
     * SQL fragment excluding {@code hidden} from a query over {@code notes n}, or "" if none.
     * Padded with a space at each end so it can be concatenated between two clauses without the
     * caller having to think about it.
     */
    static String hiddenClause(Set<String> hidden) {
        return excludeCollectionsClause(hidden);
    }

    /**
     * The same fragment under the name that describes what it does rather than who asked for it.
     *
     * <p>{@link #hiddenClause} is the common case — leave out what the user believes is shut — but
     * not the only one. The Wear projection excludes every <em>locked</em> collection rather than
     * every hidden one, because a card leaving the phone is a different question from a card
     * appearing in a list, and reading {@code hiddenClause(lockedIds)} at the call site would look
     * like a bug rather than the stricter rule it is.
     */
    static String excludeCollectionsClause(Set<String> collectionIds) {
        if (collectionIds.isEmpty()) return "";
        StringBuilder sql = new StringBuilder(" AND (n.collection_id IS NULL OR n.collection_id NOT IN (");
        for (int i = 0; i < collectionIds.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append('?');
        }
        return sql.append(")) ").toString();
    }

    /**
     * True if this note belongs to a shut collection — locked at rest and not opened this session.
     *
     * <p>For the things that hang off a note without living in the {@code notes} table. A note's
     * whiteboard is the case this exists for: its strokes are their own rows, untouched by the lock
     * migration, so nothing about the board itself says the note it belongs to is shut.
     */
    static boolean isNoteHidden(SQLiteDatabase db, String noteId) {
        if (noteId == null) return false;
        String collectionId = collectionIdOfNote(db, noteId);
        return collectionId != null
                && isLocked(db, collectionId)
                && !CollectionLock.isUnlocked(collectionId);
    }

    static String collectionIdOfNote(SQLiteDatabase db, String noteId) {
        try (Cursor c = db.rawQuery("SELECT collection_id FROM notes WHERE id = ?",
                new String[]{noteId})) {
            if (!c.moveToFirst() || c.isNull(0)) return null;
            return c.getString(0);
        }
    }

    // ---------- Conversion ----------

    static String encryptTitle(String collectionId, String title) throws GeneralSecurityException {
        if (title == null) return null;
        byte[] cipherText = CollectionCrypto.encrypt(collectionId, title.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipherText, Base64.NO_WRAP);
    }

    static byte[] encryptBody(String collectionId, String markdown) throws GeneralSecurityException {
        if (markdown == null) return null;
        return CollectionCrypto.encrypt(collectionId, markdown.getBytes(StandardCharsets.UTF_8));
    }

    static String decryptTitle(String collectionId, String stored) throws GeneralSecurityException {
        if (stored == null) return null;
        byte[] plain = CollectionCrypto.decrypt(collectionId, Base64.decode(stored, Base64.NO_WRAP));
        return new String(plain, StandardCharsets.UTF_8);
    }

    static String decryptBody(String collectionId, byte[] stored) throws GeneralSecurityException {
        if (stored == null) return null;
        return new String(CollectionCrypto.decrypt(collectionId, stored), StandardCharsets.UTF_8);
    }

    // ---------- Read-path convenience ----------

    /**
     * Decrypts for a read that has no way to report failure — a list being built, a preview being
     * rendered — returning null instead of throwing.
     *
     * <p>Null is not silence: the collection is shut on the way out, so the very next query hides
     * it rather than showing rows the app can no longer read. That is the right response to both
     * causes. If the key's authentication window closed mid-session, the collection genuinely is
     * locked again and the user should be asked; if the key is gone, its notes are unreadable and
     * showing them as anything other than absent would be a lie.
     */
    static String decryptTitleOrNull(String collectionId, String stored) {
        try {
            return decryptTitle(collectionId, stored);
        } catch (GeneralSecurityException e) {
            Log.w(TAG, "could not decrypt title; re-locking " + collectionId, e);
            CollectionLock.relock(collectionId);
            return null;
        }
    }

    /**
     * The title to show for a row a list query has already decided to keep, given the collections
     * that are encrypted at rest.
     *
     * <p>Returns the column untouched for a collection that was never locked, and null if the row
     * belongs to an open collection whose key has since gone — {@link #decryptTitleOrNull} has shut
     * the collection by then, and the caller should drop the row rather than title it with nothing.
     */
    static String titleForDisplay(Set<String> lockedIds, String collectionId, String stored) {
        if (collectionId == null || !lockedIds.contains(collectionId)) return stored;
        return decryptTitleOrNull(collectionId, stored);
    }

    static String decryptBodyOrNull(String collectionId, byte[] stored) {
        try {
            return decryptBody(collectionId, stored);
        } catch (GeneralSecurityException e) {
            Log.w(TAG, "could not decrypt body; re-locking " + collectionId, e);
            CollectionLock.relock(collectionId);
            return null;
        }
    }
}
