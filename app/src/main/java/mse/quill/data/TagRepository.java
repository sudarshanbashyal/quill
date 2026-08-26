package mse.quill.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import mse.quill.data.model.Tag;

public class TagRepository {

    public interface OnTagCreated { void onCreated(Tag tag); }
    public interface OnTagsLoaded { void onLoaded(List<Tag> tags); }

    private final AppDatabase appDatabase;
    private final AppExecutors executors;

    public TagRepository(Context context) {
        this.appDatabase = AppDatabase.getInstance(context.getApplicationContext());
        this.executors = AppExecutors.getInstance();
    }

    public void createTag(String name, int color, OnTagCreated cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();

            ContentValues cv = new ContentValues();
            cv.put("id", id);
            cv.put("name", name);
            cv.put("color", color);
            cv.put("created_at", now);
            db.insert("tags", null, cv);

            Tag tag = new Tag();
            tag.id = id;
            tag.name = name;
            tag.color = color;
            tag.createdAt = now;

            if (cb != null) executors.mainThread(() -> cb.onCreated(tag));
        });
    }

    public void loadAllTags(OnTagsLoaded cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getReadableDatabase();
            Cursor c = db.rawQuery(
                    "SELECT id, name, color, created_at FROM tags ORDER BY name COLLATE NOCASE ASC", null);
            List<Tag> tags = new ArrayList<>();
            try {
                while (c.moveToNext()) tags.add(readTag(c));
            } finally {
                c.close();
            }
            if (cb != null) executors.mainThread(() -> cb.onLoaded(tags));
        });
    }

    public void loadTagsForNote(String noteId, OnTagsLoaded cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getReadableDatabase();
            Cursor c = db.rawQuery(
                    "SELECT t.id, t.name, t.color, t.created_at FROM note_tags nt " +
                            "JOIN tags t ON t.id = nt.tag_id " +
                            "WHERE nt.note_id = ? ORDER BY t.name COLLATE NOCASE ASC",
                    new String[]{noteId});
            List<Tag> tags = new ArrayList<>();
            try {
                while (c.moveToNext()) tags.add(readTag(c));
            } finally {
                c.close();
            }
            if (cb != null) executors.mainThread(() -> cb.onLoaded(tags));
        });
    }

    public void setNoteTags(String noteId, List<String> tagIds, Runnable onDone) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete("note_tags", "note_id = ?", new String[]{noteId});
                for (String tagId : tagIds) {
                    ContentValues cv = new ContentValues();
                    cv.put("note_id", noteId);
                    cv.put("tag_id", tagId);
                    db.insert("note_tags", null, cv);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            if (onDone != null) executors.mainThread(onDone);
        });
    }

    private static Tag readTag(Cursor c) {
        Tag tag = new Tag();
        tag.id = c.getString(0);
        tag.name = c.getString(1);
        tag.color = c.getInt(2);
        tag.createdAt = c.getLong(3);
        return tag;
    }
}
