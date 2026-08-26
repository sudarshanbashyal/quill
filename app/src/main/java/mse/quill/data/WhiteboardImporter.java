package mse.quill.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import mse.quill.data.model.Stroke;
import mse.quill.data.model.Whiteboard;
import mse.quill.data.model.WhiteboardText;
import mse.quill.share.WhiteboardBundleReader;

/**
 * Turns a {@code .quillboard} bundle into a new whiteboard on this device.
 *
 * <p>Same load-bearing rule as {@link NoteImporter}: an imported board is a new board, not the
 * sender's. Fresh id, fresh stroke and text ids — {@code authorId} isn't carried at all, since it
 * is a live-collaboration field the bundle format never wrote (see {@code WhiteboardBundle}'s
 * class doc) — so two people who "have the same board" hold two independent ones.
 */
public final class WhiteboardImporter {

    private static final String TAG = "WhiteboardImporter";

    public enum Failure { NOT_A_BUNDLE, UNREADABLE }

    public interface OnImported {
        void onImported(String whiteboardId, String title);

        void onFailed(Failure failure);
    }

    private final Context appContext;
    private final AppDatabase appDatabase;
    private final AppExecutors executors;
    private final StrokeRepository strokeRepository;
    private final WhiteboardTextRepository textRepository;

    public WhiteboardImporter(Context context) {
        this.appContext = context.getApplicationContext();
        this.appDatabase = AppDatabase.getInstance(appContext);
        this.executors = AppExecutors.getInstance();
        this.strokeRepository = new StrokeRepository(appContext);
        this.textRepository = new WhiteboardTextRepository(appContext);
    }

    public void importFrom(Uri source, OnImported cb) {
        executors.diskIO(() -> {
            WhiteboardBundleReader.Contents contents;
            try (InputStream in = appContext.getContentResolver().openInputStream(source)) {
                if (in == null) throw new IOException("no stream for " + source);
                contents = WhiteboardBundleReader.read(in);
            } catch (WhiteboardBundleReader.InvalidBundleException e) {
                Log.w(TAG, "not a Quill whiteboard: " + source, e);
                fail(cb, Failure.NOT_A_BUNDLE);
                return;
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "could not read " + source, e);
                fail(cb, e instanceof IOException ? Failure.NOT_A_BUNDLE : Failure.UNREADABLE);
                return;
            }

            String whiteboardId = insert(contents);
            if (whiteboardId == null) {
                fail(cb, Failure.UNREADABLE);
                return;
            }
            String title = contents.title;
            if (cb != null) executors.mainThread(() -> cb.onImported(whiteboardId, title));
        });
    }

    private String insert(WhiteboardBundleReader.Contents contents) {
        String whiteboardId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        Whiteboard wb = new Whiteboard();
        wb.id = whiteboardId;
        wb.noteId = null;   // lands standalone on Home, same as an imported note lands loose
        wb.title = contents.title == null || contents.title.isEmpty() ? null : contents.title;
        wb.createdAt = contents.createdAt > 0 ? contents.createdAt : now;
        wb.updatedAt = now;
        wb.background = contents.background;

        SQLiteDatabase db = appDatabase.getWritableDatabase();
        db.beginTransaction();
        try {
            new WhiteboardRepository(appContext).insertSync(wb);
            for (Stroke stroke : contents.strokes) {
                stroke.id = UUID.randomUUID().toString();
                stroke.whiteboardId = whiteboardId;
                stroke.authorId = null;
                strokeRepository.insertStrokeSync(stroke);
            }
            for (WhiteboardText text : contents.texts) {
                text.id = UUID.randomUUID().toString();
                text.whiteboardId = whiteboardId;
                text.authorId = null;
                textRepository.insertSync(text);
            }
            db.setTransactionSuccessful();
        } catch (SQLiteException e) {
            Log.e(TAG, "could not insert imported whiteboard", e);
            return null;
        } finally {
            db.endTransaction();
        }
        return whiteboardId;
    }

    private void fail(OnImported cb, Failure failure) {
        if (cb != null) executors.mainThread(() -> cb.onFailed(failure));
    }
}
