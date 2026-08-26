package mse.quill.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import mse.quill.data.model.Stroke;
import mse.quill.data.model.Whiteboard;
import mse.quill.data.model.WhiteboardText;
import mse.quill.data.serialization.NoteDocument;
import mse.quill.share.BundleReader;
import mse.quill.share.QuillBundle;
import mse.quill.data.model.NoteSegment;

/**
 * Turns a {@code .quill} bundle into a note on this device.
 *
 * <p>The load-bearing decision, and the one that makes the whole sharing design cheap: <b>an
 * imported note is a new note, not the sender's note</b>. It gets a fresh id, fresh asset ids and
 * fresh files. Two people who "have the same note" hold two independent notes, so there is no
 * shared identity to reconcile and no conflict domain — which is why none of the vector-clock or
 * outbox machinery in the schema is needed to share a note.
 *
 * <p>A repository in everything but name — same {@link AppExecutors} thread, same callback shape —
 * kept separate from {@link NoteRepository} because it owns a workflow rather than a table: it
 * writes notes, media assets and tags in one transaction and reads a file to do it.
 */
public final class NoteImporter {

    private static final String TAG = "NoteImporter";
    private static final int COPY_BUFFER = 16 * 1024;

    /** Why an import didn't produce a note. The UI distinguishes these because the user's next
     *  move differs: a wrong file is theirs to fix, a failed write is not. */
    public enum Failure { NOT_A_BUNDLE, UNREADABLE }

    public interface OnImported {
        /** @param noteId the new note, ready to open. */
        void onImported(String noteId, String title);

        void onFailed(Failure failure);
    }

    private final Context appContext;
    private final AppDatabase appDatabase;
    private final AppExecutors executors;

    public NoteImporter(Context context) {
        this.appContext = context.getApplicationContext();
        this.appDatabase = AppDatabase.getInstance(appContext);
        this.executors = AppExecutors.getInstance();
    }

    /**
     * Reads the bundle at {@code source} and inserts it.
     *
     * <p>{@code source} is a {@code content://} uri from the document picker, so it is opened
     * through the resolver rather than as a path — the picker grants access to a stream, and on
     * most providers there is no file behind it to open anyway.
     */
    public void importFrom(Uri source, OnImported cb) {
        executors.diskIO(() -> {
            BundleReader.Contents contents;
            try (InputStream in = appContext.getContentResolver().openInputStream(source)) {
                if (in == null) throw new IOException("no stream for " + source);
                contents = BundleReader.read(in, appContext.getCacheDir());
            } catch (BundleReader.InvalidBundleException e) {
                Log.w(TAG, "not a Quill bundle: " + source, e);
                fail(cb, Failure.NOT_A_BUNDLE);
                return;
            } catch (IOException | RuntimeException e) {
                // A zip that isn't a zip surfaces here as a ZipException rather than as ours, and
                // to the user it is the same mistake — they picked the wrong file.
                Log.w(TAG, "could not read " + source, e);
                fail(cb, e instanceof IOException ? Failure.NOT_A_BUNDLE : Failure.UNREADABLE);
                return;
            }

            try {
                String noteId = insertBundle(contents, null);
                if (noteId == null) {
                    fail(cb, Failure.UNREADABLE);
                    return;
                }
                String title = contents.title;
                if (cb != null) executors.mainThread(() -> cb.onImported(noteId, title));
            } finally {
                // Whatever the outcome: insertBundle() moves the media it keeps out of here first,
                // so this only ever removes what wasn't wanted or what a failed insert left behind.
                contents.discard();
            }
        });
    }

    /**
     * Inserts an already-parsed bundle. Public (and callable off the {@link AppExecutors} disk
     * thread it's normally reached through) so {@code data/CollectionImporter} can insert several
     * notes from one {@code .quillpack} without a round trip through a {@code Uri} for each — the
     * outer bundle has already unpacked them into memory.
     *
     * @param collectionId the collection to file the note into, or null to leave it loose on Home
     *                     (the single-note import path always passes null: a shared note's sender
     *                     folders are theirs, see {@link QuillBundle} — a collection import is the
     *                     one case where the destination is chosen for the note).
     * @return the new note's id, or null if nothing was written.
     */
    public String insertBundle(BundleReader.Contents contents, String collectionId) {
        String noteId = UUID.randomUUID().toString();

        // Media moves before the transaction opens, because it is file I/O and holding a SQLite
        // write transaction open across it would block every other repository on the shared disk
        // thread for as long as the copies take. A crash in between leaves orphaned files in
        // private storage — the cheaper of the two failures, and the same one an interrupted
        // image embed already produces.
        Map<String, String> newIdByOldId = new HashMap<>();
        List<ContentValues> assetRows = new ArrayList<>();
        for (BundleReader.MediaEntry entry : contents.media) {
            File stored = adopt(entry);
            if (stored == null) continue;   // couldn't be copied; its embed drops out below

            String assetId = UUID.randomUUID().toString();
            newIdByOldId.put(entry.sourceId, assetId);

            ContentValues cv = new ContentValues();
            cv.put("id", assetId);
            cv.put("note_id", noteId);
            cv.put("type", entry.type);
            cv.put("file_path", stored.getAbsolutePath());
            cv.put("created_at", System.currentTimeMillis());
            if (entry.type == NoteSegment.TYPE_IMAGE) {
                cv.put("width", entry.width);
            } else if (entry.type == NoteSegment.TYPE_AUDIO) {
                cv.put("duration_ms", entry.durationMs);
            }
            assetRows.add(cv);
        }

        // Whiteboard ids are minted here too — before the rewrite, alongside the media ids — since
        // rewriteEmbedIds matches purely on the id in a quill:// line, regardless of which kind of
        // embed it names. No DB write happens yet; the rows go in inside the transaction below.
        for (BundleReader.WhiteboardEntry entry : contents.whiteboards) {
            newIdByOldId.put(entry.sourceId, UUID.randomUUID().toString());
        }

        String markdown = NoteDocument.rewriteEmbedIds(contents.markdown, newIdByOldId);
        String title = contents.title == null ? "" : contents.title;
        long now = System.currentTimeMillis();

        SQLiteDatabase db = appDatabase.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues note = new ContentValues();
            note.put("id", noteId);
            note.put("title", title);
            note.put("content_blob", markdown.getBytes(StandardCharsets.UTF_8));
            // Created when the sender created it — that date is a fact about the note and the one
            // thing the copy can honestly inherit. Updated now, because arriving here *is* this
            // note's most recent event, and it is what puts the import at the top of Home where
            // the user is looking for it.
            note.put("created_at", contents.createdAt > 0 ? contents.createdAt : now);
            note.put("updated_at", now);
            // No collection unless the caller names one (a collection import): the sender's
            // folders are theirs otherwise, and dropping a lone import into one the user didn't
            // choose hides it. It lands loose on Home and can be filed from there.
            if (collectionId != null) note.put("collection_id", collectionId);
            db.insert("notes", null, note);

            for (ContentValues asset : assetRows) {
                db.insert("note_segments", null, asset);
            }
            insertWhiteboards(noteId, contents.whiteboards, newIdByOldId, now);
            WhiteboardLinks.replace(db, noteId, markdown);
            attachTags(db, noteId, contents.tags);
            indexNoteSync(db, noteId, title, markdown);

            db.setTransactionSuccessful();
        } catch (SQLiteException e) {
            Log.e(TAG, "could not insert imported note", e);
            return null;
        } finally {
            db.endTransaction();
        }
        return noteId;
    }

    /**
     * Rebuilds each embedded whiteboard as a new, note-attached board — the same "new id, new rows"
     * rule {@link #insertBundle} applies to the note itself. {@code authorId} isn't carried: it's a
     * live-collaboration field with no meaning for a board arriving by file, the same reason
     * {@link mse.quill.share.WhiteboardBundleReader} never wrote one.
     */
    private void insertWhiteboards(String noteId, List<BundleReader.WhiteboardEntry> whiteboards,
                                   Map<String, String> newIdByOldId, long now) {
        StrokeRepository strokeRepository = new StrokeRepository(appDatabase);
        WhiteboardTextRepository textRepository = new WhiteboardTextRepository(appDatabase);

        for (BundleReader.WhiteboardEntry entry : whiteboards) {
            String whiteboardId = newIdByOldId.get(entry.sourceId);
            if (whiteboardId == null) continue;   // rewriteEmbedIds already dropped its embed line

            Whiteboard board = new Whiteboard();
            board.id = whiteboardId;
            board.noteId = noteId;
            board.title = entry.title == null || entry.title.isEmpty() ? null : entry.title;
            board.createdAt = entry.createdAt > 0 ? entry.createdAt : now;
            board.updatedAt = now;
            board.background = entry.background;
            new WhiteboardRepository(appDatabase).insertSync(board);

            for (Stroke stroke : entry.strokes) {
                stroke.id = UUID.randomUUID().toString();
                stroke.whiteboardId = whiteboardId;
                stroke.authorId = null;
                strokeRepository.insertStrokeSync(stroke);
            }
            for (WhiteboardText text : entry.texts) {
                text.id = UUID.randomUUID().toString();
                text.whiteboardId = whiteboardId;
                text.authorId = null;
                textRepository.insertSync(text);
            }
        }
    }

    /**
     * Moves one unpacked asset into the note's private storage, beside the ones the editor writes.
     *
     * <p>A rename first: the cache and files directories are on the same filesystem, so the bytes
     * never move. It can still fail — some devices split them — hence the copy behind it.
     */
    private File adopt(BundleReader.MediaEntry entry) {
        File dir = new File(appContext.getFilesDir(),
                entry.type == NoteSegment.TYPE_AUDIO ? "audio" : "images");
        if (!dir.exists() && !dir.mkdirs()) return null;

        File target = new File(dir, "import_" + UUID.randomUUID() + extensionOf(entry.file));
        if (entry.file.renameTo(target)) return target;

        try (InputStream in = new FileInputStream(entry.file);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[COPY_BUFFER];
            for (int read; (read = in.read(buffer)) != -1; ) {
                out.write(buffer, 0, read);
            }
            return target;
        } catch (IOException e) {
            Log.w(TAG, "could not store imported media", e);
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return null;
        }
    }

    /**
     * Links the note to the bundle's tags, matching on name and creating what's missing.
     *
     * <p>Name rather than id, because a tag id is local: "Lecture" on the sender's phone and
     * "Lecture" on the receiver's are the same idea with different keys, and importing by id would
     * grow a second, invisible "Lecture" every time a note arrived. Matched case-insensitively for
     * the same reason the tag list sorts that way — "lecture" is not a second tag.
     *
     * <p>An existing tag keeps its own colour. The user chose it; a note arriving from elsewhere
     * doesn't get to restyle their tag list.
     */
    private void attachTags(SQLiteDatabase db, String noteId, List<BundleReader.TagEntry> tags) {
        for (BundleReader.TagEntry tag : tags) {
            String tagId = findTagIdByName(db, tag.name);
            if (tagId == null) {
                tagId = UUID.randomUUID().toString();
                ContentValues cv = new ContentValues();
                cv.put("id", tagId);
                cv.put("name", tag.name);
                cv.put("color", tag.color);
                cv.put("created_at", System.currentTimeMillis());
                db.insert("tags", null, cv);
            }
            ContentValues link = new ContentValues();
            link.put("note_id", noteId);
            link.put("tag_id", tagId);
            db.insert("note_tags", null, link);
        }
    }

    private String findTagIdByName(SQLiteDatabase db, String name) {
        try (Cursor c = db.rawQuery(
                "SELECT id FROM tags WHERE name = ? COLLATE NOCASE LIMIT 1", new String[]{name})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    /** Same best-effort indexing as {@link NoteRepository}: an imported note has to be searchable
     *  immediately, and FTS5 being absent on a device mustn't fail the import. */
    private void indexNoteSync(SQLiteDatabase db, String noteId, String title, String markdown) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("note_id", noteId);
            cv.put("title", title);
            cv.put("body", NoteDocument.toPlainText(markdown));
            db.insert("notes_fts", null, cv);
        } catch (SQLiteException e) {
            Log.w(TAG, "notes_fts unavailable, imported note not indexed", e);
        }
    }

    private void fail(OnImported cb, Failure failure) {
        if (cb != null) executors.mainThread(() -> cb.onFailed(failure));
    }

    private static String extensionOf(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot);
    }
}
