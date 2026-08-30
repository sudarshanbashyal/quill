package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import mse.quill.data.CollectionRepository;
import mse.quill.data.NoteRepository;
import mse.quill.data.TagRepository;
import mse.quill.data.model.Collection;
import mse.quill.data.model.Tag;

/**
 * CRUD for the two repositories that had no coverage at all: collections and tags.
 *
 * <p>These were the gap Epic A named, and they are worth closing for a specific reason rather than
 * for the count — both own a <em>relationship</em> that outlives the row. Deleting a collection has
 * to leave its notes alone, and re-tagging a note has to replace the note's tags rather than
 * accumulate them. Neither is visible from the call site; both are the kind of thing that breaks
 * quietly and is noticed weeks later as "some notes lost their collection".
 *
 * <p>Everything created here is named with a per-run prefix and deleted in {@link #tearDown},
 * because this suite runs against the real {@code quill.db} on the device.
 */
@RunWith(AndroidJUnit4.class)
public class CollectionAndTagRepositoryTest {

    /** Long enough that a slow emulator is not a failure, short enough that a hang is. */
    private static final long TIMEOUT_SECONDS = 10;

    private final String runPrefix = "test-" + UUID.randomUUID() + "-";

    private Context context;
    private CollectionRepository collections;
    private TagRepository tags;
    private NoteRepository notes;

    private final List<String> collectionsToClean = new ArrayList<>();
    private final List<String> notesToClean = new ArrayList<>();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        collections = new CollectionRepository(context);
        tags = new TagRepository(context);
        notes = new NoteRepository(context);
    }

    @After
    public void tearDown() {
        for (String noteId : notesToClean) await(done -> notes.deleteNote(noteId, done));
        for (String id : collectionsToClean) await(done -> collections.deleteCollection(id, done));
        // Tags have no delete on the repository — nothing in the app removes one — so the rows
        // stay. They are inert and prefixed, which is the best this can do without inventing an
        // API the product does not have.
    }

    // ── Collections ──────────────────────────────────────────────────────────────────────

    @Test
    public void createdCollectionComesBackWithWhatItWasGiven() {
        String id = createCollection("Physics", 0xFF2196F3);

        Collection found = findCollection(id);
        assertNotNull("a collection created a moment ago is not in the list", found);
        assertEquals(name("Physics"), found.name);
        assertEquals(0xFF2196F3, found.color);
        assertFalse("a new collection should not be locked", found.biometricLocked);
        assertEquals("a new collection holds nothing", 0, found.noteCount);
        assertTrue("created_at was not stamped", found.createdAt > 0);
    }

    @Test
    public void renameChangesTheNameAndNothingElse() {
        String id = createCollection("Biolgy", 0xFF4CAF50);

        await(done -> collections.renameCollection(id, name("Biology"), done));

        Collection found = findCollection(id);
        assertNotNull(found);
        assertEquals(name("Biology"), found.name);
        assertEquals("rename should not have touched the colour", 0xFF4CAF50, found.color);
    }

    @Test
    public void noteCountFollowsTheNotesFiledInIt() {
        String id = createCollection("Chemistry", 0);
        assertEquals(0, findCollection(id).noteCount);

        createNoteIn(id, "Alkanes");
        assertEquals(1, findCollection(id).noteCount);

        String second = createNoteIn(id, "Alkenes");
        assertEquals(2, findCollection(id).noteCount);

        await(done -> notes.deleteNote(second, done));
        notesToClean.remove(second);
        assertEquals("a deleted note should stop counting", 1, findCollection(id).noteCount);
    }

    /**
     * The one that matters. A collection is a folder, not an owner — deleting it must not take
     * the notes with it.
     */
    @Test
    public void deletingACollectionKeepsItsNotesAndUnfilesThem() {
        String id = createCollection("Geology", 0);
        String noteId = createNoteIn(id, "Plate tectonics");

        await(done -> collections.deleteCollection(id, done));
        collectionsToClean.remove(id);

        assertNull("the collection is still listed after being deleted", findCollection(id));

        AtomicReference<mse.quill.data.model.Note> note = new AtomicReference<>();
        await(done -> notes.loadNote(noteId, (loaded, segments) -> {
            note.set(loaded);
            done.run();
        }));
        assertNotNull("deleting the collection deleted the note inside it", note.get());
        assertNull("the note should have been moved to Uncategorized, not left pointing at a "
                + "collection that no longer exists", note.get().collectionId);
    }

    @Test
    public void isLockedAnswersFalseForAnUnlockedCollectionAndForNoCollectionAtAll() {
        String id = createCollection("Astronomy", 0);

        assertFalse(lockStateOf(id));
        // A note filed nowhere is not in a locked collection; there is no collection to lock.
        assertFalse(lockStateOf(null));
    }

    // ── Tags ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void createdTagComesBackWithWhatItWasGiven() {
        Tag created = createTag("revision", 0xFFE91E63);

        assertNotNull(created.id);
        assertEquals(name("revision"), created.name);
        assertEquals(0xFFE91E63, created.color);
        assertTrue("created_at was not stamped", created.createdAt > 0);

        assertNotNull("a tag created a moment ago is not in the list", findTag(created.id));
    }

    @Test
    public void tagsComeBackSortedByNameIgnoringCase() {
        // Created deliberately out of order, and with a lowercase name between two capitals, so a
        // plain byte sort would put "beta" after "Gamma".
        createTag("zzz-Gamma", 0);
        createTag("zzz-beta", 0);
        createTag("zzz-Alpha", 0);

        List<String> ours = new ArrayList<>();
        AtomicReference<List<Tag>> loaded = new AtomicReference<>();
        await(done -> tags.loadAllTags(list -> {
            loaded.set(list);
            done.run();
        }));
        for (Tag tag : loaded.get()) {
            if (tag.name.startsWith(runPrefix)) ours.add(tag.name);
        }

        assertEquals(Arrays.asList(name("zzz-Alpha"), name("zzz-beta"), name("zzz-Gamma")), ours);
    }

    /**
     * The other one that matters. {@code setNoteTags} is a <em>replace</em>, not an append, and the
     * difference only shows on the second call.
     */
    @Test
    public void settingATagListReplacesTheNotesTagsRatherThanAddingToThem() {
        String noteId = createNoteIn(null, "Thermodynamics");
        Tag first = createTag("first-law", 0);
        Tag second = createTag("second-law", 0);
        Tag third = createTag("entropy", 0);

        await(done -> tags.setNoteTags(noteId, Arrays.asList(first.id, second.id), done));
        assertEquals(namesOf(tagsOn(noteId)), Arrays.asList(name("first-law"), name("second-law")));

        await(done -> tags.setNoteTags(noteId, Collections.singletonList(third.id), done));
        assertEquals("the earlier tags were kept instead of replaced",
                Collections.singletonList(name("entropy")), namesOf(tagsOn(noteId)));

        await(done -> tags.setNoteTags(noteId, Collections.emptyList(), done));
        assertTrue("clearing a note's tags left rows behind", tagsOn(noteId).isEmpty());
    }

    @Test
    public void aTagOnOneNoteIsNotOnAnother() {
        String tagged = createNoteIn(null, "Tagged");
        String untagged = createNoteIn(null, "Untagged");
        Tag tag = createTag("shared", 0);

        await(done -> tags.setNoteTags(tagged, Collections.singletonList(tag.id), done));

        assertEquals(1, tagsOn(tagged).size());
        assertTrue("the tag leaked onto a note it was never put on", tagsOn(untagged).isEmpty());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────

    /** Prefixes a name so this run's rows are identifiable among the device's real ones. */
    private String name(String base) {
        return runPrefix + base;
    }

    private String createCollection(String baseName, int color) {
        AtomicReference<String> id = new AtomicReference<>();
        await(done -> collections.createCollection(name(baseName), color, created -> {
            id.set(created);
            done.run();
        }));
        assertNotNull("createCollection never called back with an id", id.get());
        collectionsToClean.add(id.get());
        return id.get();
    }

    private Tag createTag(String baseName, int color) {
        AtomicReference<Tag> tag = new AtomicReference<>();
        await(done -> tags.createTag(name(baseName), color, created -> {
            tag.set(created);
            done.run();
        }));
        assertNotNull("createTag never called back with a tag", tag.get());
        return tag.get();
    }

    private String createNoteIn(String collectionId, String title) {
        String noteId = UUID.randomUUID().toString();
        await(done -> notes.createNote(noteId, name(title), collectionId, done));
        notesToClean.add(noteId);
        return noteId;
    }

    private Collection findCollection(String id) {
        AtomicReference<List<Collection>> all = new AtomicReference<>();
        await(done -> collections.loadCollections(list -> {
            all.set(list);
            done.run();
        }));
        for (Collection c : all.get()) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }

    private Tag findTag(String id) {
        AtomicReference<List<Tag>> all = new AtomicReference<>();
        await(done -> tags.loadAllTags(list -> {
            all.set(list);
            done.run();
        }));
        for (Tag t : all.get()) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }

    private List<Tag> tagsOn(String noteId) {
        AtomicReference<List<Tag>> found = new AtomicReference<>();
        await(done -> tags.loadTagsForNote(noteId, list -> {
            found.set(list);
            done.run();
        }));
        return found.get();
    }

    private boolean lockStateOf(String collectionId) {
        AtomicReference<Boolean> locked = new AtomicReference<>();
        await(done -> collections.isLocked(collectionId, value -> {
            locked.set(value);
            done.run();
        }));
        return locked.get();
    }

    private static List<String> namesOf(List<Tag> tags) {
        List<String> names = new ArrayList<>();
        for (Tag tag : tags) names.add(tag.name);
        return names;
    }

    /** Runs an async repository call and blocks until its callback fires. */
    private interface AsyncCall { void start(Runnable onDone); }

    private static void await(AsyncCall call) {
        CountDownLatch latch = new CountDownLatch(1);
        call.start(latch::countDown);
        try {
            assertTrue("repository callback never fired", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for a repository callback", e);
        }
    }
}
