package mse.quill.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import mse.quill.R;
import mse.quill.audio.ReadAloud;

/**
 * Tells the watch what the phone's voice is doing, so the wrist can have transport controls.
 *
 * <p>The watch could have been left to poll, and it would have been less code. It would also have
 * been wrong in the case the controls exist for: a reading paused from the phone, or one that
 * simply reached the end, has to close the watch's control screen, and a screen that finds out on
 * its next poll is a screen offering to pause something that stopped talking a while ago.
 *
 * <p>So this rides {@link ReadAloud}'s own listener — the same signal the phone's now-playing bar
 * redraws on — and publishes a {@code DataItem} the watch converges on. It is attached for the life
 * of the process rather than owned by a screen, because the interesting case is precisely the one
 * where no screen is up: a reading started from the watch, with the phone in a pocket.
 */
public final class WearReadStatePublisher {

    private static final String TAG = "WearReadState";

    /**
     * How far the voice has to move before a progress-only change is worth a publish.
     *
     * <p>{@link ReadAloud} fires on every chunk, which for a long note is a few times a second, and
     * a {@code DataItem} per chunk would be a sync storm to move a bar the watch is not showing at
     * that resolution anyway. Anything that changes what the controls <em>say</em> — playing,
     * stopped, a different note — is published immediately regardless.
     */
    private static final float PROGRESS_STEP = 0.05f;

    private static Listener attached;

    private WearReadStatePublisher() {}

    /**
     * Starts publishing, if it isn't already, and publishes once now.
     *
     * <p>Called from the phone's own startup and again whenever the watch asks for a reading — the
     * second is not redundant: the first only ran if the app was opened, and the whole point is
     * that it may not have been.
     */
    public static void ensureAttached(Context context) {
        Context appContext = context.getApplicationContext();
        if (attached == null) {
            attached = new Listener(appContext);
            ReadAloud.addListener(attached);
        }
        attached.onReadAloudChanged();
    }

    /** Holds what was last put, so an unchanged state is not put again. */
    private static final class Listener implements ReadAloud.Listener {

        private final Context appContext;

        private boolean lastActive;
        private boolean lastPlaying;
        private String lastTitle;
        private float lastProgress;
        private boolean published;

        Listener(Context appContext) {
            this.appContext = appContext;
        }

        @Override
        public void onReadAloudChanged() {
            // On the main thread, where ReadAloud lives and where its state is safe to read.
            boolean active = ReadAloud.isActive();
            boolean playing = ReadAloud.isPlaying();
            String title = ReadAloud.title();
            String noteId = ReadAloud.noteId();
            float progress = ReadAloud.progress();

            if (published && active == lastActive && playing == lastPlaying
                    && equal(title, lastTitle)
                    && Math.abs(progress - lastProgress) < PROGRESS_STEP) {
                return;
            }

            lastActive = active;
            lastPlaying = playing;
            lastTitle = title;
            lastProgress = progress;
            published = true;

            // Off the main thread from here: naming the note means asking the database whether it
            // is one whose name is allowed to leave the device. The executor is a single thread,
            // so two changes in quick succession still reach the watch in the order they happened.
            AppExecutors.getInstance().diskIO(() -> {
                try {
                    put(active, playing, publishableTitle(noteId, title), progress);
                } catch (RuntimeException e) {
                    // This fires on every reading state change, including from a locked
                    // collection's note — an uncaught exception here would crash the app well
                    // past startup, not just skip a watch sync. Same policy as
                    // WearProjectionPublisher/WearNoteListPublisher's wrappers.
                    Log.w(TAG, "Could not publish the read state; nothing published this round", e);
                }
            });
        }

        /**
         * The title as the watch is allowed to see it.
         *
         * <p>A note in an encrypted collection is named to nobody, and that rule does not soften
         * because the collection happens to be open — {@link WearNoteListPublisher} states the
         * reason and this is the same question: not "should this appear on screen" but "should
         * this leave the device". A reading can only reach a private note by being started on the
         * phone, from a collection the user has just unlocked; the {@code DataItem} it would
         * publish outlives that unlock, so "Therapy notes" would sit on the watch long after the
         * collection shut again.
         *
         * <p>Only the name is withheld. The controls still work, because a reading the user
         * started deliberately is one they must be able to stop from the wrist.
         */
        private String publishableTitle(String noteId, String title) {
            if (title == null) return "";
            // A note that has never been saved has no collection to be private to.
            if (noteId == null) return title;

            SQLiteDatabase db = AppDatabase.getInstance(appContext).getWritableDatabase();
            try (Cursor c = db.rawQuery(
                    "SELECT collection_id FROM notes WHERE id = ?", new String[]{noteId})) {
                if (!c.moveToFirst() || c.isNull(0)) return title;
                return NoteCrypto.isLocked(db, c.getString(0))
                        ? appContext.getString(R.string.wear_read_private_title)
                        : title;
            }
        }

        private void put(boolean active, boolean playing, String title, float progress) {
            PutDataMapRequest request = PutDataMapRequest.create(ReadStateKeys.PATH);
            request.getDataMap().putBoolean(ReadStateKeys.KEY_ACTIVE, active);
            request.getDataMap().putBoolean(ReadStateKeys.KEY_PLAYING, playing);
            request.getDataMap().putString(ReadStateKeys.KEY_TITLE, title);
            request.getDataMap().putFloat(ReadStateKeys.KEY_PROGRESS, progress);
            request.getDataMap().putLong(
                    ReadStateKeys.KEY_UPDATED_AT, System.currentTimeMillis());

            // Urgent, unlike the note list: a watch showing a pause button for a reading that has
            // already finished is wrong on screen right now, and the default sync can sit on a
            // change for minutes.
            Wearable.getDataClient(appContext)
                    .putDataItem(request.asPutDataRequest().setUrgent())
                    // Not awaited — there is nothing to do with the answer. A missed publish is
                    // corrected by the next one, and the watch's controls fall back to what it can
                    // see for itself.
                    .addOnFailureListener(e -> Log.w(TAG, "Could not publish the read state", e));
        }

        private static boolean equal(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }
}
