package mse.quill.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import mse.quill.data.model.Whiteboard;

/**
 * Async, callback-based access to the `whiteboards` table, following the same shape as
 * NoteRepository / CollectionRepository and routing every query through the shared
 * AppExecutors.diskIO() thread.
 *
 * WhiteboardFragment still talks to the Sync methods below and {@link StrokeRepository} on its own
 * threads (see memory/requirements.md Epic A); this repository is what the Home screen uses.
 */
public class WhiteboardRepository {

    public interface OnWhiteboardCreated { void onCreated(String whiteboardId); }
    public interface OnWhiteboardsLoaded { void onLoaded(List<Whiteboard> whiteboards); }

    private final AppDatabase appDatabase;
    private final AppExecutors executors;
    /** Null for the {@link #WhiteboardRepository(AppDatabase)} form — those callers (the
     *  whiteboard screen, the thumbnailer) already run far more often than a widget needs to
     *  refresh, so only the id-mutating methods below, reached through the Context constructor,
     *  push a widget update. */
    private final Context appContext;

    public WhiteboardRepository(Context context) {
        this(AppDatabase.getInstance(context.getApplicationContext()), context.getApplicationContext());
    }

    /** For callers already holding the database — the whiteboard screen and the thumbnailer. */
    public WhiteboardRepository(AppDatabase appDatabase) {
        this(appDatabase, null);
    }

    private WhiteboardRepository(AppDatabase appDatabase, Context appContext) {
        this.appDatabase = appDatabase;
        this.appContext = appContext;
        this.executors = AppExecutors.getInstance();
    }

    /** Creates an empty standalone board (noteId null) or one attached to a note. */
    /**
     * @param background the paper style the new board starts on — see
     *                   {@code WhiteboardPreferences.defaultBackground}. Passed in rather than read
     *                   here so the repository stays clear of user preferences.
     */
    public void createWhiteboard(String title, String noteId, int background,
                                 OnWhiteboardCreated cb) {
        executors.diskIO(() -> {
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();

            ContentValues cv = new ContentValues();
            cv.put("id", id);
            cv.put("note_id", noteId);
            cv.put("title", title);
            cv.put("created_at", now);
            cv.put("updated_at", now);
            cv.put("background", background);
            appDatabase.getWritableDatabase().insert("whiteboards", null, cv);
            mse.quill.widget.WidgetUpdater.notifyWhiteboardsChanged(appContext);

            if (cb != null) executors.mainThread(() -> cb.onCreated(id));
        });
    }

    public void renameWhiteboard(String id, String newTitle, Runnable onDone) {
        executors.diskIO(() -> {
            ContentValues cv = new ContentValues();
            cv.put("title", newTitle);
            appDatabase.getWritableDatabase().update("whiteboards", cv, "id = ?", new String[]{id});
            mse.quill.widget.WidgetUpdater.notifyWhiteboardsChanged(appContext);
            if (onDone != null) executors.mainThread(onDone);
        });
    }

    /**
     * Deletes the board and everything that points at it. Hard delete, matching how collections
     * are removed — there is no trash surface for whiteboards, so a soft-deleted board would just
     * be unreachable rows.
     *
     * <p><b>All three referencing tables go first, not just the strokes.</b> Three tables carry a
     * foreign key onto {@code whiteboards.id} — {@code strokes}, {@code whiteboard_texts} and
     * {@code note_whiteboards} — and this used to clear only the first. Deleting a board with a
     * text box on it, or one embedded in any note, therefore raised
     * {@code SQLiteConstraintException} on a background thread, which is not a failed delete but a
     * dead process: nothing catches it, so the app disappeared mid-tap.
     *
     * <p>The note's Markdown is deliberately left alone. The embed line stays, and the editor
     * renders it as a board that is gone — see {@code WhiteboardSegmentView}. Rewriting somebody's
     * note because they deleted a drawing is a larger liberty than leaving a marker they can
     * remove themselves.
     */
    public void deleteWhiteboard(String id, Runnable onDone) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete("strokes", "whiteboard_id = ?", new String[]{id});
                db.delete("whiteboard_texts", "whiteboard_id = ?", new String[]{id});
                db.delete("note_whiteboards", "whiteboard_id = ?", new String[]{id});
                db.delete("whiteboards", "id = ?", new String[]{id});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            mse.quill.widget.WidgetUpdater.notifyWhiteboardsChanged(appContext);
            if (onDone != null) executors.mainThread(onDone);
        });
    }

    /**
     * How many notes embed this board. <b>Blocking — call from a background thread.</b>
     *
     * <p>Asked before the delete confirmation, so the warning can say what will be left behind
     * rather than making the user find out afterwards.
     */
    public int embeddingNoteCountSync(String whiteboardId) {
        try (Cursor c = appDatabase.getWritableDatabase().rawQuery(
                "SELECT COUNT(*) FROM note_whiteboards nw JOIN notes n ON n.id = nw.note_id "
                        + "WHERE nw.whiteboard_id = ? AND n.deleted_at IS NULL",
                new String[]{whiteboardId})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /**
     * Most recently edited first, so Home's section reads like the notes list.
     *
     * <p>Boards belonging to a shut collection's notes drop out, the same way those notes do. The
     * strokes are not encrypted by the lock — the migration converts note text and nothing else —
     * so this filter is the whole of what keeps a locked note's drawing off Home, thumbnail
     * included.
     *
     * <p>Two ways a board can belong to a note, and both are checked: {@code note_id}, set when the
     * board was created from a note, and {@link WhiteboardLinks}, which records the embeds — "Import
     * whiteboard" attaches an existing board without changing whose it is. A board that is neither
     * created from nor embedded in any note is in no collection and is never hidden.
     */
    public void loadWhiteboards(OnWhiteboardsLoaded cb) {
        executors.diskIO(() -> {
            List<Whiteboard> whiteboards = loadWhiteboardsSync();
            if (cb != null) executors.mainThread(() -> cb.onLoaded(whiteboards));
        });
    }

    /** Synchronous form of {@link #loadWhiteboards}, for callers already off the main thread. */
    public List<Whiteboard> loadWhiteboardsSync() {
        SQLiteDatabase db = appDatabase.getWritableDatabase();
        return loadWhiteboardsSync(db, NoteCrypto.hiddenCollectionIds(db));
    }

    /**
     * The boards a home-screen widget is allowed to show — <b>every locked collection is excluded,
     * open or not</b>, unlike {@link #loadWhiteboardsSync}, which hides only the collections that
     * are shut this session.
     *
     * <p>See {@code NoteRepository.loadPinnedNotesForWidgetSync} for why the widget asks the
     * stricter question. It matters more here than anywhere: a board's strokes are never encrypted
     * by the lock — this filter is the whole of what keeps a locked note's drawing off the home
     * screen, thumbnail and all.
     */
    public List<Whiteboard> loadWhiteboardsForWidgetSync() {
        SQLiteDatabase db = appDatabase.getWritableDatabase();
        return loadWhiteboardsSync(db, NoteCrypto.lockedCollectionIds(db));
    }

    /** @param excluded the collections whose boards drop out — shut this session, or locked at
     *      rest, depending on which of the two callers above asked. */
    private List<Whiteboard> loadWhiteboardsSync(SQLiteDatabase db, Set<String> excluded) {
        List<String> args = new ArrayList<>(excluded);
        args.addAll(excluded);

        Cursor c = db.rawQuery(
                "SELECT w.id, w.note_id, w.title, w.created_at, w.updated_at, w.background, " +
                        "(SELECT COUNT(*) FROM strokes s WHERE s.whiteboard_id = w.id) AS stroke_count " +
                        "FROM whiteboards w LEFT JOIN notes n ON n.id = w.note_id " +
                        // The clause passes rows whose n.collection_id is null, which an
                        // unowned board's outer join gives it for free.
                        "WHERE 1 = 1 " + NoteCrypto.excludeCollectionsClause(excluded) +
                        WhiteboardLinks.hiddenClause(excluded) +
                        "ORDER BY w.updated_at DESC, w.created_at DESC",
                args.toArray(new String[0]));
        List<Whiteboard> whiteboards = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                Whiteboard wb = new Whiteboard();
                wb.id = c.getString(0);
                wb.noteId = c.getString(1);
                wb.title = c.getString(2);
                wb.createdAt = c.getLong(3);
                wb.updatedAt = c.isNull(4) ? wb.createdAt : c.getLong(4);
                wb.background = c.getInt(5);
                wb.strokeCount = c.getInt(6);
                whiteboards.add(wb);
            }
        } finally {
            c.close();
        }
        return whiteboards;
    }

    // ── Synchronous access ───────────────────────────────────────────────────
    //
    // The whiteboard screen runs its own threads rather than AppExecutors (Epic A), and one call
    // has to be synchronous on purpose: `strokes` carries a foreign key onto the board, so the row
    // must exist before any stroke is written. These carry the Sync suffix so that calling one
    // from the UI thread reads as the mistake it would be — the threading unification itself is
    // still outstanding.

    /** Insert a new whiteboard row. Called once when a whiteboard is first created. */
    public void insertSync(Whiteboard wb) {
        ContentValues v = new ContentValues();
        v.put("id", wb.id);
        v.put("note_id", wb.noteId);
        v.put("title", wb.title);
        v.put("created_at", wb.createdAt);
        v.put("updated_at", wb.updatedAt);
        v.put("background", wb.background);
        appDatabase.getWritableDatabase().insertWithOnConflict(
                "whiteboards", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Records that the canvas changed, so Home's Whiteboards section sorts by real recency. */
    public void touchSync(String id, long updatedAt) {
        ContentValues v = new ContentValues();
        v.put("updated_at", updatedAt);
        appDatabase.getWritableDatabase().update("whiteboards", v, "id = ?", new String[]{id});
    }

    /** Sets the board's paper style (see WhiteboardView.BACKGROUND_*). */
    public void setBackgroundSync(String id, int background) {
        ContentValues v = new ContentValues();
        v.put("background", background);
        v.put("updated_at", System.currentTimeMillis());
        appDatabase.getWritableDatabase().update("whiteboards", v, "id = ?", new String[]{id});
    }

    /** Sets the board's name. A null title leaves it untitled, so it falls back to its dated
     *  display name rather than showing an empty row. */
    public void renameSync(String id, String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("updated_at", System.currentTimeMillis());
        appDatabase.getWritableDatabase().update("whiteboards", v, "id = ?", new String[]{id});
    }

    /**
     * Fetch a whiteboard by its id.
     *
     * @return null if there is no such board, or if it belongs to a note in a shut collection —
     *     which every caller here already renders as "this whiteboard is gone", the same answer a
     *     deleted board gets. Holding an id is not permission to read the board: the note embed,
     *     the thumbnailer and the share bundle all reach boards this way.
     */
    public Whiteboard getByIdSync(String id) {
        SQLiteDatabase db = appDatabase.getReadableDatabase();
        Cursor c = db.query("whiteboards", null, "id = ?", new String[]{id}, null, null, null);
        Whiteboard wb = null;
        if (c.moveToFirst()) {
            wb = fromCursor(c);
        }
        c.close();
        if (wb == null) return null;

        if (NoteCrypto.isNoteHidden(db, wb.noteId)) return null;
        return WhiteboardLinks.isHidden(db, wb.id, NoteCrypto.hiddenCollectionIds(db)) ? null : wb;
    }

    /** Fetch the whiteboard belonging to a given note (a note has at most one whiteboard). Null
     *  for a note in a shut collection, as {@link #getByIdSync}. */
    public Whiteboard getByNoteIdSync(String noteId) {
        SQLiteDatabase db = appDatabase.getReadableDatabase();
        if (NoteCrypto.isNoteHidden(db, noteId)) return null;

        Cursor c = db.query("whiteboards", null, "note_id = ?", new String[]{noteId},
                null, null, null);
        Whiteboard wb = null;
        if (c.moveToFirst()) {
            wb = fromCursor(c);
        }
        c.close();
        if (wb == null) return null;

        return WhiteboardLinks.isHidden(db, wb.id, NoteCrypto.hiddenCollectionIds(db)) ? null : wb;
    }

    static Whiteboard fromCursor(Cursor c) {
        Whiteboard wb = new Whiteboard();
        wb.id        = c.getString(c.getColumnIndexOrThrow("id"));
        wb.noteId    = c.getString(c.getColumnIndexOrThrow("note_id"));
        wb.title     = c.getString(c.getColumnIndexOrThrow("title"));
        wb.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        wb.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        wb.background = c.getInt(c.getColumnIndexOrThrow("background"));
        return wb;
    }
}
