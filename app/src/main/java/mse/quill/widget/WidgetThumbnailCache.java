package mse.quill.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Disk-backed mirror of a whiteboard's rendered preview, keyed by whiteboard id.
 *
 * <p>{@code WhiteboardThumbnails} renders through a live {@code WhiteboardView} on the main
 * thread and only ever hands the bitmap back through a callback — there is no synchronous way to
 * get one. A {@code RemoteViewsFactory.getViewAt()} call, by contrast, must return synchronously
 * and runs on a background binder thread with no view hierarchy to render through. This cache is
 * the bridge: {@code WhiteboardThumbnails} writes here every time it renders a board (see its
 * {@code render} call site), and the whiteboards widget reads here instead of rendering anything
 * itself.
 *
 * <p>A board with no file here yet — one Home has never drawn — is not left on the placeholder:
 * the widget asks for a render itself (see {@code WhiteboardsRemoteViewsService.onDataSetChanged}),
 * because otherwise every such board shows the same glyph and a screen of different boards looks
 * identical.
 */
public final class WidgetThumbnailCache {

    private WidgetThumbnailCache() {}

    private static File file(Context context, String whiteboardId) {
        File dir = new File(context.getApplicationContext().getCacheDir(), "widget_thumbs");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, whiteboardId + ".png");
    }

    /** Called off the main thread — see {@code WhiteboardThumbnails}'s disk-IO hop. */
    public static void write(Context context, String whiteboardId, Bitmap bitmap) {
        File file = file(context, whiteboardId);
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException ignored) {
            // Best-effort: the widget just falls back to its placeholder for this board.
        }
    }

    /**
     * Whether the file on disk is a picture of the board <em>as it is now</em>.
     *
     * <p>The file's own timestamp is the comparison: it is written at the moment of the render, so
     * a board drawn on since — its {@code updatedAt} bumped past that — has a picture that is
     * merely old, which reads as a wrong thumbnail rather than a missing one.
     */
    public static boolean isCurrent(Context context, String whiteboardId, long updatedAt) {
        File file = file(context, whiteboardId);
        return file.exists() && file.lastModified() >= updatedAt;
    }

    /** Synchronous read for the widget's RemoteViewsFactory. Null if nothing was ever cached. */
    public static Bitmap readSync(Context context, String whiteboardId) {
        File file = file(context, whiteboardId);
        if (!file.exists()) return null;
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }
}
