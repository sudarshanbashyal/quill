package mse.quill.data;

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import mse.quill.R;
import mse.quill.ui.notes.editor.model.AudioSegment;
import mse.quill.ui.notes.editor.model.NoteSegment;

/**
 * Receives a voice memo recorded on the watch and files it into a note.
 *
 * <p>The successor to the transcript capture this replaced, and the one thing on the watch that
 * <em>originates</em> there — everything else in the Wear companion is a projection of what the
 * phone already knows. So the audio arrives as a payload rather than a reference: there is no copy
 * of it anywhere else, and this service is where it stops being the watch's only copy.
 *
 * <p>Two steps, and both have to happen or neither is worth doing. The asset is pulled into the
 * same {@code files/audio} directory the phone's own recorder writes to, so a memo from the wrist
 * is indistinguishable from one recorded in the editor from that point on; then the segment is
 * appended <b>through {@link NoteRepository}</b> rather than by writing {@code content_blob}, for
 * the reason spelled out there — a note's body drives the media asset registry, the whiteboard
 * links and the search index, and a service writing the blob itself would get one right and three
 * wrong. The registry is what makes the file survive: an audio file with no segment pointing at it
 * is an orphan the next save deletes.
 */
public class WearAudioCaptureListenerService extends WearableListenerService {

    private static final String TAG = "WearAudioCapture";

    /** Long enough for a disk round trip, short enough not to hold a binder thread on a wedge. */
    private static final long STEP_TIMEOUT_SECONDS = 30;

    @Override
    public void onDataChanged(@NonNull DataEventBuffer events) {
        List<Capture> captures = new ArrayList<>();
        try {
            for (DataEvent event : events) {
                // Deletes come through here too — including this service's own, once it has filed
                // a memo and cleaned up after itself.
                if (event.getType() != DataEvent.TYPE_CHANGED) continue;

                Uri uri = event.getDataItem().getUri();
                if (uri.getPath() == null
                        || !uri.getPath().startsWith(AudioCaptureKeys.PATH_PREFIX)) continue;

                captures.add(new Capture(
                        uri, DataMapItem.fromDataItem(event.getDataItem()).getDataMap()));
            }
        } finally {
            // Holds a native buffer; the callback owns it and it is invalid after we return, so
            // everything wanted from it has to be copied out above.
            events.release();
        }

        // Oldest first, by the watch's clock. A batch only happens when several memos were recorded
        // away from the phone and have arrived together, and that is exactly the case where the
        // buffer's own order means nothing and the order they were spoken in means everything.
        Collections.sort(captures, (a, b) -> Long.compare(a.recordedAt, b.recordedAt));

        for (Capture capture : captures) {
            if (store(capture.map)) {
                // Only once it is safely in a note. A capture left in the store is one the phone
                // will be handed again next time it syncs, which is the behaviour we want when
                // this failed — the recording is not lost, only late.
                deleteFromStore(capture.uri);
            }
        }
    }

    /** One pending memo, copied out of the event buffer before it is released. */
    private static final class Capture {
        final Uri uri;
        final DataMap map;
        final long recordedAt;

        Capture(Uri uri, DataMap map) {
            this.uri = uri;
            this.map = map;
            this.recordedAt = map.getLong(AudioCaptureKeys.KEY_CAPTURED_AT, 0);
        }
    }

    /** Pulls the audio down and appends it to a note. Returns whether it landed. */
    private boolean store(DataMap map) {
        Asset asset = map.getAsset(AudioCaptureKeys.ASSET_AUDIO);
        if (asset == null) {
            // Nothing to file, and nothing to gain by keeping it — say it landed so it is dropped.
            Log.w(TAG, "Dropping an audio capture with no audio in it");
            return true;
        }

        File audioFile = pullAsset(asset);
        if (audioFile == null) return false;

        int durationMs = (int) map.getLong(AudioCaptureKeys.KEY_DURATION_MS, 0);
        boolean newNote = map.getBoolean(AudioCaptureKeys.KEY_NEW_NOTE, false);
        String targetNoteId = map.getString(AudioCaptureKeys.KEY_NOTE_ID);

        boolean stored = newNote
                ? appendToNewNote(audioFile.getAbsolutePath(), durationMs)
                : append(targetNoteId, audioFile.getAbsolutePath(), durationMs);
        if (!stored) {
            // The file would otherwise sit in files/audio with nothing pointing at it, and the
            // retry on the next sync writes a second copy.
            audioFile.delete();
        }
        return stored;
    }

    /**
     * Copies the asset out of the Data Layer and into the app's audio directory.
     *
     * <p>{@code getFdForAsset} is what actually moves the bytes — until it is called the asset is a
     * digest and nothing more, which is the whole point of assets: the item syncs immediately and
     * the payload follows only for a node that wants it.
     */
    private File pullAsset(Asset asset) {
        File directory = new File(getFilesDir(), "audio");
        if (!directory.exists() && !directory.mkdirs()) {
            Log.w(TAG, "Could not create the audio directory");
            return null;
        }

        // The same shape the phone's own recorder produces, because from here on it is the same
        // kind of thing — see AudioRecorder.createPrivateAudioFile.
        File destination = new File(directory, "audio_" + UUID.randomUUID() + ".m4a");

        ParcelFileDescriptor descriptor = null;
        try {
            // This callback runs on a background binder thread, so blocking here blocks the right
            // one — and this is the call that waits on the transfer.
            descriptor = Tasks.await(
                    Wearable.getDataClient(getApplicationContext()).getFdForAsset(asset),
                    STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS).getFdForAsset();
            if (descriptor == null) {
                Log.w(TAG, "The watch's audio asset had no descriptor");
                return null;
            }

            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                 OutputStream out = new FileOutputStream(destination)) {
                descriptor = null;   // the stream owns it now
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            return destination;
        } catch (Exception e) {
            // Including the interrupted and timeout cases: the watch keeps the DataItem either way,
            // so a failure here costs a retry rather than the recording.
            Log.w(TAG, "Could not pull the watch's audio", e);
            destination.delete();
            return null;
        } finally {
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException ignored) {
                    // Nothing useful to do with a descriptor that will not close.
                }
            }
        }
    }

    /**
     * Appends the memo to the chosen note, or to the inbox. Returns whether the save completed.
     *
     * <p><b>A locked destination falls back to the inbox rather than failing.</b> The watch's
     * picker never offers a note in an encrypted collection — {@link WearNoteListPublisher}
     * excludes every one of them, open or shut — so this is the gap case: the collection was
     * locked between that list being published and this memo arriving. The gap used to be minutes
     * and is now potentially hours, because a memo waits in the Data Layer for a phone to appear,
     * which is exactly why it is worth handling rather than logging.
     *
     * <p>Retrying is not an option: the lock will not lift because we asked again, and a memo
     * re-offered on every sync would never stop. Dropping it is worse — there is no other copy.
     * The inbox is outside every collection and so can always take it, which is the whole reason
     * {@link NoteRepository#inboxNoteIdSync} refuses to live in one.
     */
    private boolean append(String targetNoteId, String filePath, int durationMs) {
        NoteRepository repository = new NoteRepository(getApplicationContext());
        String inboxTitle = getString(R.string.wear_inbox_note_title);

        try {
            // The chosen note if it still exists, the inbox otherwise. The watch picked from a list
            // that may be hours old, and a memo landing in the wrong note is recoverable where one
            // the phone refused to store is not.
            String noteId = targetNoteId != null && repository.noteExistsSync(targetNoteId)
                    ? targetNoteId
                    : repository.inboxNoteIdSync(inboxTitle);

            Outcome outcome = appendTo(repository, noteId, inboxTitle, filePath, durationMs);
            if (outcome != Outcome.LOCKED) return outcome == Outcome.SAVED;

            String inboxId = repository.inboxNoteIdSync(inboxTitle);
            if (inboxId.equals(noteId)) {
                // Unreachable unless the inbox has been moved into a collection by hand, which
                // nothing in the app offers. Guarded anyway: the alternative is a loop.
                Log.w(TAG, "The inbox itself is locked; the memo could not be written");
                return false;
            }
            Log.w(TAG, "The chosen note is locked; filing the memo in the inbox instead");
            return appendTo(repository, inboxId, inboxTitle, filePath, durationMs) == Outcome.SAVED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted while storing a memo", e);
            return false;
        }
    }

    /**
     * Files the memo in a note that did not exist until now.
     *
     * <p>Created with an <b>empty title</b>, which is not an oversight but the app's convention for
     * an untitled note: the editor leaves the field blank and offers the generated name as a hint,
     * and every list resolves it through {@link mse.quill.util.NoteDisplayUtils#resolveTitle} to
     * "Untitled Note - &lt;date&gt;". Storing a literal name here — "Voice note", or the date
     * spelled out — would make a note that renames itself the moment the user typed a title, and
     * one that sorted and searched unlike every other untitled note in the app.
     *
     * <p>In no collection, for the same reason the inbox is in none: the watch cannot know which
     * collections are private, and a new note is not the place to guess.
     */
    private boolean appendToNewNote(String filePath, int durationMs) {
        NoteRepository repository = new NoteRepository(getApplicationContext());
        String noteId = NoteRepository.newNoteId();
        // Asynchronous, but ordered: createNote and the load inside appendTo both run on the
        // repository's single disk thread, so the insert has landed before the load looks for it.
        repository.createNote(noteId, "", null, null);

        try {
            // The empty title survives the round trip: appendTo only substitutes the inbox's name
            // when the note has no row at all, and this one does.
            return appendTo(repository, noteId, "", filePath, durationMs) == Outcome.SAVED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted while storing a memo in a new note", e);
            return false;
        }
    }

    /** One attempt at one note. {@link Outcome#LOCKED} is the caller's cue to try the inbox. */
    private Outcome appendTo(NoteRepository repository, String noteId, String inboxTitle,
                             String filePath, int durationMs) throws InterruptedException {
        AtomicReference<String> loadedTitle = new AtomicReference<>();
        AtomicReference<List<NoteSegment>> loaded = new AtomicReference<>();
        CountDownLatch loadedLatch = new CountDownLatch(1);
        repository.loadNote(noteId, (note, segments) -> {
            // The note's own title, not the inbox's — saving a chosen note under "Inbox" would
            // quietly rename it.
            if (note != null) loadedTitle.set(note.title);
            loaded.set(segments);
            loadedLatch.countDown();
        });
        if (!loadedLatch.await(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.w(TAG, "Timed out loading the note to append to");
            return Outcome.FAILED;
        }

        List<NoteSegment> segments = loaded.get();
        if (segments == null) {
            Log.w(TAG, "The target note could not be read");
            return Outcome.FAILED;
        }

        segments.add(new AudioSegment(filePath, durationMs));

        String title = loadedTitle.get() == null ? inboxTitle : loadedTitle.get();

        AtomicReference<Outcome> result = new AtomicReference<>(Outcome.FAILED);
        CountDownLatch savedLatch = new CountDownLatch(1);
        repository.saveNote(noteId, title, segments, new NoteRepository.OnNoteSaved() {
            @Override
            public void onSaved() {
                result.set(Outcome.SAVED);
                savedLatch.countDown();
            }

            @Override
            public void onNeedsUnlock() {
                result.set(Outcome.LOCKED);
                savedLatch.countDown();
            }
        });
        if (!savedLatch.await(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.w(TAG, "Timed out saving the memo");
            return Outcome.FAILED;
        }
        return result.get();
    }

    /**
     * What became of one attempt.
     *
     * <p>{@link #LOCKED} is deliberately not folded into {@link #FAILED}: a failure is worth
     * retrying on the next sync and a lock is not, and the two want opposite things done with the
     * {@code DataItem} afterwards.
     */
    private enum Outcome { SAVED, LOCKED, FAILED }

    /** Clears the capture out of the Data Layer now that the phone owns a copy. */
    private void deleteFromStore(Uri uri) {
        try {
            Tasks.await(Wearable.getDataClient(getApplicationContext()).deleteDataItems(uri),
                    STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // The item stays and is offered again on the next sync, which files a duplicate memo.
            // Annoying, and much better than the alternative failure — deleting one we had not
            // actually stored.
            Log.w(TAG, "Could not clear a filed capture from the Data Layer", e);
        }
    }
}
