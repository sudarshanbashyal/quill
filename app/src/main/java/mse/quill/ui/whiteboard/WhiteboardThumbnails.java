package mse.quill.ui.whiteboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.LruCache;
import android.view.View;

import java.util.List;

import mse.quill.data.AppDatabase;
import mse.quill.data.AppExecutors;
import mse.quill.data.StrokeRepository;
import mse.quill.data.WhiteboardRepository;
import mse.quill.data.WhiteboardTextRepository;
import mse.quill.data.model.Stroke;
import mse.quill.data.model.Whiteboard;
import mse.quill.data.model.WhiteboardText;

/**
 * Draws the little preview of a board that Home's cards show.
 *
 * <p>Home used to show a glyph and a stroke count instead, because a real preview means reading
 * every visible board's strokes just to draw a list. Two things make it affordable: the cache
 * below, and the fact that only the cards actually on screen ask for one.
 *
 * <p>The cache key includes the board's {@code updatedAt}, so a board that has been drawn on since
 * gets a fresh key and a fresh render — there is no invalidation to remember to call. Bitmaps are
 * measured in kilobytes against a slice of the app's memory rather than counted, since a wide
 * drawing and a small one cost very different amounts.
 */
public final class WhiteboardThumbnails {

    /** Longest side of a rendered preview. Big enough to stay sharp on a card, small enough to keep. */
    private static final int MAX_PX = 480;

    /** The offscreen canvas the preview is drawn through — only its aspect ratio matters. */
    private static final int RENDER_WIDTH = 480;
    private static final int RENDER_HEIGHT = 300;

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(cacheSizeKb()) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };

    private WhiteboardThumbnails() {}

    public interface OnReady {
        void onThumbnail(Bitmap thumbnail);
    }

    /**
     * Hands back the board's preview, from cache if it has one and off the disk thread if not.
     *
     * <p>{@code onReady} is called on the main thread, and only for boards that still have
     * something to draw — an empty board reports null so the card can show its placeholder rather
     * than a blank rectangle pretending to be a drawing.
     */
    public static void load(Context context, Whiteboard whiteboard, OnReady onReady) {
        String key = key(whiteboard);
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            onReady.onThumbnail(cached);
            return;
        }

        Context appContext = context.getApplicationContext();
        AppExecutors executors = AppExecutors.getInstance();
        executors.diskIO(() -> {
            AppDatabase db = AppDatabase.getInstance(appContext);
            List<Stroke> strokes = new StrokeRepository(db).getByWhiteboard(whiteboard.id);
            List<WhiteboardText> texts = new WhiteboardTextRepository(db).getByWhiteboard(whiteboard.id);
            if (strokes.isEmpty() && texts.isEmpty()) {
                executors.mainThread(() -> onReady.onThumbnail(null));
                return;
            }
            // Rendering happens on the main thread: it goes through a WhiteboardView, and a View
            // is not built to be touched from two threads. Only the reads above are off it, which
            // is the part that would actually block.
            executors.mainThread(() -> {
                Bitmap rendered = render(appContext, whiteboard, strokes, texts);
                if (rendered != null) {
                    CACHE.put(key, rendered);
                    // Mirrors this render to disk so the whiteboards widget — which can't run a
                    // WhiteboardView off-screen the way this method does — has something to read
                    // synchronously. See mse.quill.widget.WidgetThumbnailCache.
                    executors.diskIO(() -> {
                        mse.quill.widget.WidgetThumbnailCache.write(
                                appContext, whiteboard.id, rendered);
                        // The widget reads that file rather than rendering anything, so a board
                        // edited since the widget last drew keeps showing the old picture until it
                        // is asked again. Here rather than on every canvas change: a re-render only
                        // happens when the in-memory cache misses, which is exactly when the
                        // picture on disk has actually changed.
                        mse.quill.widget.WidgetUpdater.notifyWhiteboardsChanged(appContext);
                    });
                }
                onReady.onThumbnail(rendered);
            });
        });
    }

    /** Delivered on the main thread. A board that has been deleted since reports a null board. */
    public interface OnPreviewReady {
        void onPreview(Whiteboard board, Bitmap thumbnail);
    }

    /**
     * The same preview, for callers holding only an id — a note's whiteboard embed knows the board
     * it points at but nothing about it, not even whether it still exists.
     */
    public static void loadById(Context context, String whiteboardId, OnPreviewReady onReady) {
        Context appContext = context.getApplicationContext();
        AppExecutors executors = AppExecutors.getInstance();
        executors.diskIO(() -> {
            Whiteboard board = new WhiteboardRepository(AppDatabase.getInstance(appContext))
                    .getByIdSync(whiteboardId);
            executors.mainThread(() -> {
                if (board == null) {
                    onReady.onPreview(null, null);
                    return;
                }
                load(appContext, board, thumbnail -> onReady.onPreview(board, thumbnail));
            });
        });
    }

    private static Bitmap render(Context context, Whiteboard whiteboard,
                                 List<Stroke> strokes, List<WhiteboardText> texts) {
        WhiteboardView canvas = new WhiteboardView(context);
        canvas.setBackgroundStyle(whiteboard.background);
        canvas.measure(View.MeasureSpec.makeMeasureSpec(RENDER_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(RENDER_HEIGHT, View.MeasureSpec.EXACTLY));
        canvas.layout(0, 0, RENDER_WIDTH, RENDER_HEIGHT);
        canvas.loadTexts(texts);
        canvas.loadStrokes(strokes);
        return canvas.renderThumbnail(MAX_PX);
    }

    private static String key(Whiteboard whiteboard) {
        return whiteboard.id + "@" + whiteboard.updatedAt + "@" + whiteboard.background;
    }

    private static int cacheSizeKb() {
        return (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
    }
}
