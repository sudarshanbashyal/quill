package mse.quill.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import mse.quill.data.model.Collection;

public class CollectionRepository {

    public interface OnCollectionCreated { void onCreated(String collectionId); }
    public interface OnCollectionsLoaded { void onLoaded(List<Collection> collections); }

    private final AppDatabase appDatabase;
    private final AppExecutors executors;

    public CollectionRepository(Context context) {
        this.appDatabase = AppDatabase.getInstance(context.getApplicationContext());
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

            if (cb != null) executors.mainThread(() -> cb.onCreated(id));
        });
    }

    public void renameCollection(String id, String newName, Runnable onDone) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("name", newName);
            db.update("collections", cv, "id = ?", new String[]{id});
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
            if (onDone != null) executors.mainThread(onDone);
        });
    }

    public void loadCollections(OnCollectionsLoaded cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            Cursor c = db.rawQuery(
                    "SELECT c.id, c.name, c.color, c.created_at, c.biometric_locked, " +
                            "(SELECT COUNT(*) FROM notes n WHERE n.collection_id = c.id AND n.deleted_at IS NULL) AS note_count, " +
                            "(SELECT MAX(n.updated_at) FROM notes n WHERE n.collection_id = c.id AND n.deleted_at IS NULL) AS last_activity " +
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
                    collections.add(collection);
                }
            } finally {
                c.close();
            }
            if (cb != null) executors.mainThread(() -> cb.onLoaded(collections));
        });
    }

}
