package mse.quill.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import mse.quill.share.BundleReader;
import mse.quill.share.CollectionBundleReader;
import mse.quill.util.ColorUtils;

/**
 * Turns a {@code .quillpack} bundle into a new collection, importing each member note through the
 * same {@link NoteImporter} a lone {@code .quill} uses.
 *
 * <p>New collection id, same as a note gets a new id — two people who "have the same collection"
 * end up with two independent ones, so there is nothing to reconcile. A locked collection is never
 * the source of a pack in the first place ({@code NoteEditorFragment} blocks sharing out of one),
 * so the new collection always starts unlocked.
 *
 * <p>One malformed member note does not fail the whole pack — the notes around it in someone's
 * export still deserve to arrive. What's lost is reported back as a count, not silently.
 */
public final class CollectionImporter {

    private static final String TAG = "CollectionImporter";

    public enum Failure { NOT_A_BUNDLE, UNREADABLE }

    public interface OnImported {
        /** @param imported how many of the pack's notes actually landed; may be less than the
         *                  pack's declared count if a member note was corrupt. */
        void onImported(String collectionId, String name, int imported, int total);

        void onFailed(Failure failure);
    }

    private final Context appContext;
    private final AppDatabase appDatabase;
    private final AppExecutors executors;
    private final NoteImporter noteImporter;

    public CollectionImporter(Context context) {
        this.appContext = context.getApplicationContext();
        this.appDatabase = AppDatabase.getInstance(appContext);
        this.executors = AppExecutors.getInstance();
        this.noteImporter = new NoteImporter(appContext);
    }

    public void importFrom(Uri source, OnImported cb) {
        executors.diskIO(() -> {
            CollectionBundleReader.Contents contents;
            try (InputStream in = appContext.getContentResolver().openInputStream(source)) {
                if (in == null) throw new IOException("no stream for " + source);
                contents = CollectionBundleReader.read(in);
            } catch (CollectionBundleReader.InvalidBundleException e) {
                Log.w(TAG, "not a Quill collection: " + source, e);
                fail(cb, Failure.NOT_A_BUNDLE);
                return;
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "could not read " + source, e);
                fail(cb, e instanceof IOException ? Failure.NOT_A_BUNDLE : Failure.UNREADABLE);
                return;
            }

            String collectionId = UUID.randomUUID().toString();
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("id", collectionId);
            cv.put("name", contents.name == null || contents.name.isEmpty()
                    ? appContext.getString(mse.quill.R.string.imported_collection_untitled)
                    : contents.name);
            cv.put("color", contents.color != 0 ? contents.color : ColorUtils.randomPaletteColor(appContext));
            cv.put("created_at", System.currentTimeMillis());
            cv.put("biometric_locked", 0);
            db.insert("collections", null, cv);

            int imported = 0;
            for (byte[] noteBytes : contents.noteBundles) {
                BundleReader.Contents noteContents;
                try {
                    noteContents = BundleReader.read(new ByteArrayInputStream(noteBytes), appContext.getCacheDir());
                } catch (IOException e) {
                    Log.w(TAG, "a note in the pack could not be read, skipping it", e);
                    continue;
                }
                try {
                    if (noteImporter.insertBundle(noteContents, collectionId) != null) imported++;
                } finally {
                    noteContents.discard();
                }
            }

            String name = contents.name;
            int total = contents.noteBundles.size();
            int finalImported = imported;
            if (cb != null) executors.mainThread(() -> cb.onImported(collectionId, name, finalImported, total));
        });
    }

    private void fail(OnImported cb, Failure failure) {
        if (cb != null) executors.mainThread(() -> cb.onFailed(failure));
    }
}
