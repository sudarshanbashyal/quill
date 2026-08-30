package mse.quill.data.wear;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import mse.quill.util.NoteDisplayUtils;
import mse.quill.data.AppDatabase;
import mse.quill.data.NoteCrypto;
import mse.quill.sync.NoteListKeys;

/**
 * Publishes the notes the watch can pick from — for dictating into, and for reading aloud.
 *
 * <p>The sibling of {@link WearProjectionPublisher}, and it inherits that class's rule verbatim:
 * <b>every locked collection is excluded, open or not.</b> The question here is not "should this
 * appear on screen" but "should this leave the device", and a title is not exempt from it — a
 * picker listing "Therapy notes" has disclosed the thing the encryption was for, without ever
 * opening it.
 *
 * <p>Because the exclusion is by collection id rather than by lock state, nothing here can hold
 * ciphertext, so — unlike {@code loadDecks} — there is no decryption step to forget.
 */
public final class WearNoteListPublisher {

    private static final String TAG = "WearNoteList";

    private WearNoteListPublisher() {}

    /** Builds and publishes the list. <b>Blocking — call from a background thread.</b> */
    public static void publishSync(Context context) {
        // See WearProjectionPublisher.publishSync's identical wrapper for why this catches
        // everything: called from MainActivity.onCreate on every launch, uncaught here means the
        // app never gets to a screen, not just a missed sync.
        try {
            publishSyncOrThrow(context);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not build the note list; nothing published this round", e);
        }
    }

    private static void publishSyncOrThrow(Context context) {
        Context appContext = context.getApplicationContext();
        SQLiteDatabase db = AppDatabase.getInstance(appContext).getReadableDatabase();

        Set<String> locked = NoteCrypto.lockedCollectionIds(db);
        List<String> args = new ArrayList<>(locked);

        List<String> ids = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                // Aliased n, and it has to be: the exclusion clause qualifies its columns that way,
                // the same as every other query it is pasted into. Without the alias this compiles
                // to "no such column: n.collection_id" — and only once a collection has been
                // locked, since the clause is empty until then.
                "SELECT n.id, n.title, n.created_at FROM notes n "
                        + "WHERE n.deleted_at IS NULL "
                        + NoteCrypto.excludeCollectionsClause(locked)
                        // Most recently touched first, so the cap drops the notes furthest from
                        // whatever the user is actually working on.
                        + "ORDER BY n.updated_at DESC LIMIT " + NoteListKeys.MAX_NOTES,
                args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                ids.add(c.getString(0));
                // Resolved here for the same reason the deck titles are: an untitled note stores
                // an empty title and the fallback is a localised date string that needs a Context.
                titles.add(NoteDisplayUtils.resolveTitle(
                        appContext, c.isNull(1) ? null : c.getString(1), c.getLong(2)));
            }
        }

        PutDataMapRequest request = PutDataMapRequest.create(NoteListKeys.PATH);
        request.getDataMap().putLong(NoteListKeys.KEY_GENERATED_AT, System.currentTimeMillis());
        request.getDataMap().putStringArray(
                NoteListKeys.KEY_NOTE_IDS, ids.toArray(new String[0]));
        request.getDataMap().putStringArray(
                NoteListKeys.KEY_NOTE_TITLES, titles.toArray(new String[0]));

        // Urgent, and it has to be. This was unhurried on the reasoning that "the list only has to
        // be right by the next time a picker opens" — true when the only thing that changed a note
        // was the phone, and false the moment the watch could create one. A memo recorded on the
        // wrist now makes a note, and the user's next act is to open the picker and look for it.
        // The Data Layer will sit on an unurgent change for minutes, which is how a note that had
        // been saved perfectly well appeared to have vanished.
        PutDataRequest put = request.asPutDataRequest().setUrgent();

        try {
            Tasks.await(Wearable.getDataClient(appContext).putDataItem(put), 15, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Same reasoning as the projection's: a watch that missed one publish gets the next,
            // and a picker showing a slightly old list is better than an error state.
            Log.w(TAG, "Could not publish the note list", e);
        }
    }
}
