package mse.quill.data;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import mse.quill.audio.AudioPlayback;
import mse.quill.audio.ReadAloud;
import mse.quill.audio.ReadPlaylist;
import mse.quill.data.model.Note;
import mse.quill.ui.notes.editor.model.NoteSegment;
import mse.quill.util.NoteDisplayUtils;

/**
 * Starts reading a note aloud, on the phone, because the watch asked.
 *
 * <p>The watch sends an id and nothing else. Everything that makes a reading happen lives here —
 * the text, the {@code TextToSpeech} engine — so the request is a command rather than a transfer.
 *
 * <p>Stopping it again is {@link WearReadControlListenerService}'s job, and what the watch's
 * buttons are drawn from is {@link WearReadStatePublisher}'s. The three are separate because they
 * are three different conversations — a command out, a command back, a state — but this one
 * attaches the publisher before it starts speaking, so a reading is never audible on the phone
 * without the watch being told it exists.
 */
public class WearReadListenerService extends WearableListenerService {

    private static final String TAG = "WearRead";

    private static final long LOAD_TIMEOUT_SECONDS = 20;

    @Override
    public void onMessageReceived(@NonNull MessageEvent event) {
        if (!ReadRequestKeys.PATH.equals(event.getPath())) return;

        DataMap map;
        try {
            map = DataMap.fromByteArray(event.getData());
        } catch (RuntimeException e) {
            Log.w(TAG, "Dropping a read request with an unreadable payload", e);
            return;
        }

        String noteId = map.getString(ReadRequestKeys.KEY_NOTE_ID);
        if (noteId == null) {
            Log.w(TAG, "Dropping a read request with no note id");
            return;
        }

        NoteRepository repository = new NoteRepository(getApplicationContext());
        AtomicReference<Note> noteRef = new AtomicReference<>();
        AtomicReference<List<NoteSegment>> segmentsRef = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);

        repository.loadNote(noteId, (note, segments) -> {
            noteRef.set(note);
            segmentsRef.set(segments);
            loaded.countDown();
        });

        try {
            // Already on a background binder thread, so this blocks the right one.
            if (!loaded.await(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out loading the note to read");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        Note note = noteRef.get();
        List<NoteSegment> segments = segmentsRef.get();
        if (note == null || segments == null) {
            // Deleted between the list being published and this arriving.
            Log.w(TAG, "Read request for a note that no longer exists: " + noteId);
            return;
        }

        ReadPlaylist playlist = ReadPlaylist.fromSegments(segments);
        if (playlist.isEmpty()) {
            // An empty note, or one whose collection was locked after the list was published — the
            // decrypt fails soft and leaves nothing to say. Either way there is no reading to
            // start, and starting a silent one would leave a media card that never ends.
            Log.w(TAG, "Nothing to read in note " + noteId);
            return;
        }

        String title = NoteDisplayUtils.resolveTitle(
                getApplicationContext(), note.title, note.createdAt);

        // On the main thread: TextToSpeech binds to an engine and posts its callbacks there, and
        // ReadAloud is a process-wide singleton the UI also drives.
        new Handler(Looper.getMainLooper()).post(() -> {
            // Attached before the voice starts, so the watch hears about this reading and not only
            // about whatever it does next. The phone may never have been opened in this process,
            // in which case nothing else has done it.
            WearReadStatePublisher.ensureAttached(getApplicationContext());
            // One voice at a time, the same rule the editor's own button follows: a recording
            // playing under a note being read is noise, and both would fight for the same bar.
            AudioPlayback.get(getApplicationContext()).close();
            ReadAloud.start(getApplicationContext(), noteId, title, playlist);
        });
    }
}
