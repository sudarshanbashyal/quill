package mse.quill.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import mse.quill.data.DataChangeNotifier.Change;
import mse.quill.data.model.Collection;

public class CollectionRepository {

    public interface OnCollectionCreated { void onCreated(String collectionId); }
    public interface OnCollectionsLoaded { void onLoaded(List<Collection> collections); }

    private final AppDatabase appDatabase;
    private final AppExecutors executors;
    private final Context appContext;

    public CollectionRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.appDatabase = AppDatabase.getInstance(appContext);
        this.executors = AppExecutors.getInstance();
    }

    public void createCollection(String name, int color, OnCollectionCreated cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            String id = UUID.randomUUID().toString();

            ContentValues cv = new ContentValues();
            cv.put("id", id);
            cv.put("name", name);
            cv.put("color", color);
            cv.put("created_at", System.currentTimeMillis());
            cv.put("biometric_locked", 0);
            db.insert("collections", null, cv);
            DataChangeNotifier.getInstance().notifyChanged(Change.COLLECTIONS);

            if (cb != null) executors.mainThread(() -> cb.onCreated(id));
        });
    }

    public interface OnLockChecked { void onChecked(boolean locked); }

    /**
     * Whether a collection is biometric-locked — the guard on sharing anything out of it.
     *
     * <p>Nothing sets {@code biometric_locked} yet (Epic B owns the lock flow), so today this
     * always answers false. It is asked anyway rather than deferred, because the boundary belongs
     * with the feature that has to respect it: a locked collection's notes must not leave the
     * device as a plaintext bundle, and wiring that in later means remembering to.
     *
     * <p>A null id — a note filed nowhere — is not locked. There is no collection to lock it.
     * The answer is still posted to the main thread rather than handed back inline, so that this
     * method keeps one shape: <b>a callback-taking method must never invoke its callback before it
     * returns.</b> Answering a null id synchronously and everything else asynchronously meant a
     * caller could not know which it would get, which is exactly the arrangement that produces a
     * re-entrancy bug once and then hides.
     */
    public void isLocked(String collectionId, OnLockChecked cb) {
        if (cb == null) return;
        if (collectionId == null) {
            executors.mainThread(() -> cb.onChecked(false));
            return;
        }
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getReadableDatabase();
            boolean locked;
            try (Cursor c = db.rawQuery("SELECT biometric_locked FROM collections WHERE id = ?",
                    new String[]{collectionId})) {
                locked = c.moveToFirst() && c.getInt(0) != 0;
            }
            executors.mainThread(() -> cb.onChecked(locked));
        });
    }

    public void renameCollection(String id, String newName, Runnable onDone) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("name", newName);
            db.update("collections", cv, "id = ?", new String[]{id});
            DataChangeNotifier.getInstance().notifyChanged(Change.COLLECTIONS);
            if (onDone != null) executors.mainThread(onDone);
        });
    }

    /** Moves member notes to Uncategorized (collection_id = NULL), then deletes the collection. */
    public void deleteCollection(String id, Runnable onDone) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            db.beginTransaction();
            try {
                ContentValues cv = new ContentValues();
                cv.putNull("collection_id");
                db.update("notes", cv, "collection_id = ?", new String[]{id});

                db.delete("collections", "id = ?", new String[]{id});

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            DataChangeNotifier.getInstance().notifyChanged(Change.COLLECTIONS);
            if (onDone != null) executors.mainThread(onDone);
        });
    }

    public void loadCollections(OnCollectionsLoaded cb) {
        executors.diskIO(() -> {
            List<Collection> collections = loadCollectionsSync();
            if (cb != null) executors.mainThread(() -> cb.onLoaded(collections));
        });
    }

    /** Synchronous form of {@link #loadCollections}, for the collections widget's
     *  RemoteViewsFactory, which Android already runs off the main thread. */
    public List<Collection> loadCollectionsSync() {
        SQLiteDatabase db = appDatabase.getReadableDatabase();
        // Flashcards and quizzes hang off notes, not collections, so both counts reach the
        // collection through the note that owns them — and skip deleted notes for the same
        // reason note_count does, or a collection emptied into the trash would still claim
        // to hold cards. Orphaned cards are skipped for the same reason again: the card exists
        // as a row, but there is nothing behind it to review.
        Cursor c = db.rawQuery(
                "SELECT c.id, c.name, c.color, c.created_at, c.biometric_locked, " +
                        "(SELECT COUNT(*) FROM notes n WHERE n.collection_id = c.id AND n.deleted_at IS NULL) AS note_count, " +
                        "(SELECT MAX(n.updated_at) FROM notes n WHERE n.collection_id = c.id AND n.deleted_at IS NULL) AS last_activity, " +
                        "(SELECT COUNT(*) FROM flashcards f JOIN notes n ON f.note_id = n.id " +
                        "   WHERE n.collection_id = c.id AND n.deleted_at IS NULL " +
                        "     AND f.orphaned_at IS NULL) AS flashcard_count, " +
                        "(SELECT COUNT(*) FROM quizzes q JOIN notes n ON q.note_id = n.id " +
                        "   WHERE n.collection_id = c.id AND n.deleted_at IS NULL) AS quiz_count " +
                        "FROM collections c ORDER BY c.created_at ASC",
                null);
        List<Collection> collections = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                Collection collection = new Collection();
                collection.id = c.getString(0);
                collection.name = c.getString(1);
                collection.color = c.getInt(2);
                collection.createdAt = c.getLong(3);
                collection.biometricLocked = c.getInt(4) != 0;
                collection.noteCount = c.getInt(5);
                collection.lastActivityAt = c.isNull(6) ? collection.createdAt : c.getLong(6);
                collection.flashcardCount = c.getInt(7);
                collection.quizCount = c.getInt(8);
                collections.add(collection);
            }
        } finally {
            c.close();
        }
        return collections;
    }

}
