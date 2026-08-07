package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import mse.quill.data.NoteRepository;
import mse.quill.data.model.Note;
import mse.quill.ui.notes.editor.model.AudioSegment;
import mse.quill.ui.notes.editor.model.HeadingMarker;
import mse.quill.ui.notes.editor.model.ImageSegment;
import mse.quill.ui.notes.editor.model.NoteSegment;
import mse.quill.ui.notes.editor.model.TextSegment;

/**
 * End-to-end coverage of the storage layer: segments → Markdown in {@code notes.content_blob}
 * plus asset rows, and back again.
 */
@RunWith(AndroidJUnit4.class)
public class NoteRepositoryMarkdownTest {

    private static final long TIMEOUT_SECONDS = 10;

    private NoteRepository repository;
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        repository = new NoteRepository(context);
    }

    // ── Blocking wrappers around the repository's async API ─────────────────

    private String createNote(String title) throws InterruptedException {
        // The caller mints the id now — createNote takes it rather than handing one back.
        String noteId = java.util.UUID.randomUUID().toString();
        CountDownLatch latch = new CountDownLatch(1);
        repository.createNote(noteId, title, null, latch::countDown);
        assertTrue("createNote timed out", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return noteId;
    }

    private void saveNote(String noteId, String title, List<NoteSegment> segments)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        repository.saveNote(noteId, title, segments, latch::countDown);
        assertTrue("saveNote timed out", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private List<NoteSegment> loadSegments(String noteId) throws InterruptedException {
        AtomicReference<List<NoteSegment>> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        repository.loadNote(noteId, (note, segments) -> {
            result.set(segments);
            latch.countDown();
        });
        assertTrue("loadNote timed out", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return result.get();
    }

    private Note loadNoteFromList(String noteId) throws InterruptedException {
        AtomicReference<List<Note>> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        repository.loadNotes(null, notes -> {
            result.set(notes);
            latch.countDown();
        });
        assertTrue("loadNotes timed out", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        for (Note note : result.get()) {
            if (note.id.equals(noteId)) return note;
        }
        return null;
    }

    private static TextSegment text(String content) {
        return new TextSegment(new SpannableStringBuilder(content));
    }

    private File mediaFile(String name) throws IOException {
        File file = new File(context.getCacheDir(), name);
        assertTrue("could not create " + name, file.createNewFile() || file.exists());
        return file;
    }

    /** Reads updated_at straight from the table — the value Home sorts and labels rows by. */
    private long updatedAt(String noteId) {
        try (android.database.Cursor c = mse.quill.data.AppDatabase.getInstance(context)
                .getReadableDatabase().query("notes", new String[]{"updated_at"},
                        "id = ?", new String[]{noteId}, null, null, null)) {
            assertTrue("note row missing", c.moveToFirst());
            return c.getLong(0);
        }
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    public void savingUnchangedContentLeavesUpdatedAtAlone() throws Exception {
        String noteId = createNote("Lecture 4");
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text("Kernels are inner products in disguise"));
        saveNote(noteId, "Lecture 4", segments);
        long afterFirstSave = updatedAt(noteId);

        Thread.sleep(30);
        // What the editor does on pause whether or not anything was typed. Opening a note and
        // backing out of it used to report "Updated now" and jump it to the top of Home.
        saveNote(noteId, "Lecture 4", segments);

        assertEquals(afterFirstSave, updatedAt(noteId));
    }

    @Test
    public void savingChangedContentStillBumpsUpdatedAt() throws Exception {
        String noteId = createNote("Lecture 4");
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text("first"));
        saveNote(noteId, "Lecture 4", segments);
        long afterFirstSave = updatedAt(noteId);

        Thread.sleep(30);
        List<NoteSegment> edited = new ArrayList<>();
        edited.add(text("second"));
        saveNote(noteId, "Lecture 4", edited);

        assertTrue("a real edit must still count as an update", updatedAt(noteId) > afterFirstSave);
    }

    @Test
    public void aRenameCountsAsAChange() throws Exception {
        String noteId = createNote("Before");
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text("body"));
        saveNote(noteId, "Before", segments);
        long afterFirstSave = updatedAt(noteId);

        Thread.sleep(30);
        saveNote(noteId, "After", segments);

        assertTrue(updatedAt(noteId) > afterFirstSave);
    }

    @Test
    public void anUntouchedNewNoteIsStillIndexedForSearch() throws Exception {
        // Some emulator images ship without fts5, and AppDatabase skips the table when that
        // happens — there is then no index to assert about. See the note on notes_fts creation.
        org.junit.Assume.assumeTrue("no fts5 on this image", hasSearchIndex());

        // createNote writes no FTS row, so the first save has to go through even when it would
        // write identical values — otherwise the note is unsearchable.
        String noteId = createNote("Findable");
        saveNote(noteId, "Findable", new ArrayList<>());

        try (android.database.Cursor c = mse.quill.data.AppDatabase.getInstance(context)
                .getReadableDatabase().query("notes_fts", new String[]{"note_id"},
                        "note_id = ?", new String[]{noteId}, null, null, null)) {
            assertTrue("a saved note must reach notes_fts", c.moveToFirst());
        }
    }

    private boolean hasSearchIndex() {
        try (android.database.Cursor c = mse.quill.data.AppDatabase.getInstance(context)
                .getReadableDatabase().rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='notes_fts'",
                        null)) {
            return c.moveToFirst();
        }
    }

    @Test
    public void formattedTextSurvivesSaveAndReload() throws Exception {
        String noteId = createNote("Formatting");

        SpannableStringBuilder content =
                new SpannableStringBuilder(HeadingMarker.forLevel(HeadingMarker.H1) + "Title\nbold here");
        int boldStart = content.length() - 9;
        content.setSpan(new StyleSpan(Typeface.BOLD), boldStart, boldStart + 4,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        List<NoteSegment> segments = new ArrayList<>();
        segments.add(new TextSegment(content));
        saveNote(noteId, "Formatting", segments);

        List<NoteSegment> reloaded = loadSegments(noteId);
        assertEquals(1, reloaded.size());
        Spannable restored = ((TextSegment) reloaded.get(0)).content;

        assertEquals(content.toString(), restored.toString());
        StyleSpan[] bold = restored.getSpans(0, restored.length(), StyleSpan.class);
        assertEquals("expected exactly one bold span", 1, bold.length);
        assertEquals(boldStart, restored.getSpanStart(bold[0]));
        assertEquals(boldStart + 4, restored.getSpanEnd(bold[0]));
    }

    @Test
    public void embedsSurviveSaveAndReloadInOrder() throws Exception {
        String noteId = createNote("Embeds");
        File image = mediaFile("test-image.jpg");
        File audio = mediaFile("test-audio.m4a");

        ImageSegment imageSegment = new ImageSegment(image.getAbsolutePath());
        imageSegment.displayWidth = 480;
        AudioSegment audioSegment = new AudioSegment(audio.getAbsolutePath(), 4200);

        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text("before"));
        segments.add(imageSegment);
        segments.add(text("between"));
        segments.add(audioSegment);
        segments.add(text("after"));
        saveNote(noteId, "Embeds", segments);

        List<NoteSegment> reloaded = loadSegments(noteId);
        assertEquals(5, reloaded.size());
        assertEquals("before", ((TextSegment) reloaded.get(0)).content.toString());

        ImageSegment restoredImage = (ImageSegment) reloaded.get(1);
        assertEquals(imageSegment.id, restoredImage.id);
        assertEquals(image.getAbsolutePath(), restoredImage.filePath);
        assertEquals("display width must come back from the asset row", 480, restoredImage.displayWidth);

        assertEquals("between", ((TextSegment) reloaded.get(2)).content.toString());

        AudioSegment restoredAudio = (AudioSegment) reloaded.get(3);
        assertEquals(audioSegment.id, restoredAudio.id);
        assertEquals("duration must come back from the asset row", 4200, restoredAudio.durationMs);

        assertEquals("after", ((TextSegment) reloaded.get(4)).content.toString());
    }

    /** Asset ids must be stable, or each save would re-key every embed in the document. */
    @Test
    public void assetIdsAreStableAcrossRepeatedSaves() throws Exception {
        String noteId = createNote("Stability");
        File image = mediaFile("stable-image.jpg");

        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text("caption"));
        segments.add(new ImageSegment(image.getAbsolutePath()));
        saveNote(noteId, "Stability", segments);

        List<NoteSegment> firstLoad = loadSegments(noteId);
        String idAfterFirstSave = firstLoad.get(1).id;

        saveNote(noteId, "Stability", firstLoad);
        List<NoteSegment> secondLoad = loadSegments(noteId);

        assertEquals(2, secondLoad.size());
        assertEquals(idAfterFirstSave, secondLoad.get(1).id);
    }

    /** Dropping an embed must delete the backing file once nothing references it. */
    @Test
    public void removingAnEmbedDeletesItsFile() throws Exception {
        String noteId = createNote("Orphans");
        File image = mediaFile("orphan-image.jpg");

        List<NoteSegment> withImage = new ArrayList<>();
        withImage.add(text("caption"));
        withImage.add(new ImageSegment(image.getAbsolutePath()));
        saveNote(noteId, "Orphans", withImage);
        assertTrue("file should still exist while referenced", image.exists());

        List<NoteSegment> withoutImage = new ArrayList<>();
        withoutImage.add(text("caption"));
        saveNote(noteId, "Orphans", withoutImage);

        assertFalse("orphaned media file should have been deleted", image.exists());
        assertEquals(1, loadSegments(noteId).size());
    }

    @Test
    public void previewComesFromTheMarkdownDocument() throws Exception {
        String noteId = createNote("Preview");

        List<NoteSegment> segments = new ArrayList<>();
        segments.add(new TextSegment(new SpannableStringBuilder(
                HeadingMarker.forLevel(HeadingMarker.H1) + "Heading line\nbody")));
        saveNote(noteId, "Preview", segments);

        Note note = loadNoteFromList(noteId);
        assertNotNull("note should appear in the list", note);
        assertEquals("Heading line", note.preview);
    }

    @Test
    public void emptyNoteLoadsAsSingleEmptyTextSegment() throws Exception {
        String noteId = createNote("Empty");
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text(""));
        saveNote(noteId, "Empty", segments);

        List<NoteSegment> reloaded = loadSegments(noteId);
        assertEquals(1, reloaded.size());
        assertEquals("", ((TextSegment) reloaded.get(0)).content.toString());
    }

    @Test
    public void blankLinesInsideATextSegmentSurvive() throws Exception {
        String noteId = createNote("Blank lines");
        List<NoteSegment> segments = new ArrayList<>();
        segments.add(text("first\n\n\n\nlast"));
        saveNote(noteId, "Blank lines", segments);

        List<NoteSegment> reloaded = loadSegments(noteId);
        assertEquals(1, reloaded.size());
        assertEquals("first\n\n\n\nlast", ((TextSegment) reloaded.get(0)).content.toString());
    }
}
