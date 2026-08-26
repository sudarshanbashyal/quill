package mse.quill.data.wear;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import mse.quill.data.model.DueCard;
import mse.quill.data.FlashcardRepository;
import mse.quill.data.DueProjection;
import mse.quill.data.DueProjectionKeys;

/**
 * Publishes today's due cards to the paired watch.
 *
 * <p><b>One {@code DataItem}, replaced whole.</b> Not one per deck and not an append-only log: the
 * watch is holding a projection, so the only correct answer to "what changed" is "all of it, as of
 * now". A partial update would give the watch a way to hold half of one projection and half of
 * another, and no way to tell.
 *
 * <p>Publishing while nothing is paired is deliberately not an error. The Data Layer stores the
 * item locally and syncs it when a watch appears, so the first thing a newly-paired watch receives
 * is the current state rather than nothing until the next review.
 */
public final class WearProjectionPublisher {

    private static final String TAG = "WearProjection";

    private WearProjectionPublisher() {}

    /**
     * Builds and publishes the projection. <b>Blocking — call from a background thread.</b>
     *
     * <p>Callers are the daily reminder worker (which has already woken to count what's due) and
     * anything that changes the schedule, which in practice means answering a card.
     */
    public static void publishSync(Context context) {
        // Called from MainActivity.onCreate on every launch, on a background thread with nothing
        // else catching for it — an uncaught exception here doesn't just skip one publish, it
        // takes the whole app down before it ever gets to a screen. Best-effort background sync
        // has to fail silently the same way the network call below already does; a wide catch
        // here is that same policy applied to the query building it depends on, not a new one.
        try {
            publishSyncOrThrow(context);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not build the due projection; nothing published this round", e);
        }
    }

    private static void publishSyncOrThrow(Context context) {
        Context appContext = context.getApplicationContext();
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();

        List<DueCard> cards = new FlashcardRepository(appContext).dueProjectionSync(now, zone);

        PutDataMapRequest request = PutDataMapRequest.create(DueProjectionKeys.PATH);
        request.getDataMap().putLong(DueProjectionKeys.KEY_GENERATED_AT, now);
        request.getDataMap().putLong(DueProjectionKeys.KEY_HORIZON,
                DueProjection.endOfDayExclusive(now, zone));

        int count = cards.size();
        String[] ids = new String[count];
        String[] fronts = new String[count];
        String[] backs = new String[count];
        long[] dueAt = new long[count];
        String[] noteIds = new String[count];
        String[] noteTitles = new String[count];
        for (int i = 0; i < count; i++) {
            DueCard card = cards.get(i);
            ids[i] = card.id;
            fronts[i] = card.front;
            backs[i] = card.back;
            dueAt[i] = card.dueAt;
            // Never null on the wire: DataMap round-trips a null element fine, but the watch would
            // then have to decide what an unnamed deck is called, which is the phone's job.
            noteIds[i] = card.noteId == null ? "" : card.noteId;
            noteTitles[i] = card.noteTitle == null ? "" : card.noteTitle;
        }
        request.getDataMap().putStringArray(DueProjectionKeys.KEY_CARD_IDS, ids);
        request.getDataMap().putStringArray(DueProjectionKeys.KEY_CARD_FRONTS, fronts);
        request.getDataMap().putStringArray(DueProjectionKeys.KEY_CARD_BACKS, backs);
        request.getDataMap().putLongArray(DueProjectionKeys.KEY_CARD_DUE_AT, dueAt);
        request.getDataMap().putStringArray(DueProjectionKeys.KEY_CARD_NOTE_IDS, noteIds);
        request.getDataMap().putStringArray(DueProjectionKeys.KEY_CARD_NOTE_TITLES, noteTitles);

        // setUrgent: the tile and the complication are showing the previous number until this
        // lands, and the Data Layer's unhurried default can sit on a change for minutes.
        PutDataRequest put = request.asPutDataRequest().setUrgent();

        try {
            Tasks.await(Wearable.getDataClient(appContext).putDataItem(put), 15, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Nothing the user can do and nothing worth interrupting them for — a watch that
            // missed one publish gets the next one, and the surfaces keep showing the last good
            // number rather than an error state that would be wrong five minutes later.
            Log.w(TAG, "Could not publish the due projection", e);
        }
    }

    /**
     * The same, but only if the projection could have changed — always true today, and a named
     * seam for when it isn't.
     *
     * <p>Kept separate from {@link #publishSync} so that call sites read as intent ("the schedule
     * moved") rather than as mechanism, and so a future "skip if identical to the last publish"
     * has one place to live.
     */
    public static void publishAfterScheduleChange(Context context) {
        publishSync(context);
    }
}
