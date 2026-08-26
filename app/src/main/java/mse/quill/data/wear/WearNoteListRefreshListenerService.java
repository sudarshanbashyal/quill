package mse.quill.data.wear;

import androidx.annotation.NonNull;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import mse.quill.data.NoteListKeys;

/**
 * Rebuilds the watch's note list because the watch asked.
 *
 * <p>The list is normally <em>pushed</em>: every mutation that changes what belongs on it — a save,
 * a delete, a move between collections, a collection being locked — republishes. This service is
 * the acknowledgement that pushing is a promise the phone cannot always keep. A publish can be lost
 * to a process death mid-write or a pairing that was down at the time, and nothing about the
 * resulting {@code DataItem} says it is stale; a list a day old and a list a second old are the
 * same bytes with a different timestamp inside.
 *
 * <p>So the watch asks whenever it is about to show the list, and the answer comes back on the
 * ordinary path. There is no payload in either direction: the request is "rebuild", and the reply
 * is the list itself, arriving the way it always does.
 *
 * <p>Cheap enough to be worth doing unconditionally — one indexed query over at most
 * {@link NoteListKeys#MAX_NOTES} rows and a {@code DataItem} put the Data Layer drops on the floor
 * if the bytes match what is already there.
 */
public class WearNoteListRefreshListenerService extends WearableListenerService {

    @Override
    public void onMessageReceived(@NonNull MessageEvent event) {
        if (!NoteListKeys.REFRESH_PATH.equals(event.getPath())) return;
        // Already on a background binder thread, which is the one publishSync wants.
        WearNoteListPublisher.publishSync(getApplicationContext());
    }
}
