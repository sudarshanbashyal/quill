package mse.quill.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mse.quill.MainActivity;
import mse.quill.R;
import mse.quill.data.WhiteboardRepository;
import mse.quill.data.model.Whiteboard;
import mse.quill.ui.whiteboard.WhiteboardThumbnails;
import mse.quill.util.NoteDisplayUtils;
import mse.quill.util.RelativeTime;
import mse.quill.DeepLinkRouter;

public class WhiteboardsRemoteViewsService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(this);
    }

    private static class Factory implements RemoteViewsFactory {
        private static final String TAG = "WhiteboardsWidget";

        /**
         * How many boards one reload may send off to be drawn.
         *
         * <p>The factory loads every board, not just the rows on screen, and a render costs a read
         * of the board's strokes and a pass through a WhiteboardView on the main thread — so a
         * library of fifty boards would otherwise queue fifty of them the moment the widget is
         * added. Each batch refreshes the widget when it lands, which brings the next batch through
         * here, so the whole list still fills in; it arrives a few boards at a time instead of all
         * at once. Comfortably more than the grid shows before scrolling.
         */
        private static final int MAX_RENDERS_PER_PASS = 6;

        private final Context context;
        private List<Whiteboard> whiteboards = new ArrayList<>();

        /**
         * Boards a render has already been asked for, keyed by id <em>and</em> the version of the
         * board that was asked about, so an edit asks again but a refresh doesn't.
         *
         * <p>Held per factory rather than statically: the factory lives exactly as long as the
         * launcher's adapter connection, so the set is dropped with it instead of outliving the
         * widget. A render in flight has not written its file yet, and without this every refresh
         * arriving in the meantime — and each finished render sends one — would start the same
         * render over again.
         */
        private final Set<String> renderRequested = new HashSet<>();

        Factory(Context context) {
            this.context = context;
        }

        @Override public void onCreate() {}

        // See PinnedNotesRemoteViewsService's onDataSetChanged for why every throwing branch is
        // caught here: this runs on a binder thread inside this app's own process, and an
        // uncaught exception here crashes the whole app, not just the widget.
        @Override public void onDataSetChanged() {
            try {
                // The stricter query: every locked collection's boards stay off the home screen,
                // whether or not the app currently considers that collection open. See
                // WhiteboardRepository.loadWhiteboardsForWidgetSync.
                whiteboards = new WhiteboardRepository(context).loadWhiteboardsForWidgetSync();
            } catch (RuntimeException e) {
                Log.e(TAG, "loadWhiteboardsForWidgetSync failed, showing an empty list", e);
                whiteboards = new ArrayList<>();
            }
            requestMissingThumbnails();
        }

        /**
         * Fills in the pictures this widget has nothing to draw for.
         *
         * <p>The disk mirror only ever held boards Home had happened to draw, so a board created
         * and never scrolled past — or any board at all, after the cache was cleared — fell back
         * to {@code ic_whiteboard}, and several such boards side by side were the same picture
         * repeated. Each render writes its file and refreshes this widget, which comes back
         * through here with a real thumbnail to show and nothing left to ask for.
         *
         * <p>Asking is all that happens here: the render is someone else's thread, so this stays a
         * few file-timestamp checks on the binder thread rather than a wait for a canvas.
         */
        private void requestMissingThumbnails() {
            int asked = 0;
            for (Whiteboard board : whiteboards) {
                if (asked >= MAX_RENDERS_PER_PASS) return;
                if (WidgetThumbnailCache.isCurrent(context, board.id, board.updatedAt)) continue;
                // An empty board never produces a bitmap, so its key stays in the set and it is
                // asked about once — the placeholder is the right picture for it anyway.
                if (!renderRequested.add(board.id + "@" + board.updatedAt)) continue;
                WhiteboardThumbnails.cacheForWidget(context, board);
                asked++;
            }
        }

        @Override public void onDestroy() { whiteboards = new ArrayList<>(); }

        @Override public int getCount() { return whiteboards.size(); }

        @Override public RemoteViews getViewAt(int position) {
            try {
                Whiteboard board = whiteboards.get(position);
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_whiteboard_item);
                views.setTextViewText(R.id.widget_item_title,
                        NoteDisplayUtils.resolveWhiteboardTitle(context, board));
                views.setTextViewText(R.id.widget_item_subtitle,
                        context.getString(R.string.updated_relative_format,
                                RelativeTime.past(context, board.updatedAt)));

                // Not a live render — RemoteViewsFactory has to return synchronously and there is no
                // view hierarchy here to draw a WhiteboardView through. See WidgetThumbnailCache.
                Bitmap thumbnail = WidgetThumbnailCache.readSync(context, board.id);
                if (thumbnail != null) {
                    // An Icon rather than setImageViewBitmap, and the difference is visible: a
                    // bitmap set that way is not sent with the row, it is put in the RemoteViews
                    // bitmap cache and the row refers to it by index — and every row built here
                    // starts a cache of its own, so every row asks for index 0. Let the launcher
                    // reload this grid while it is mid-fetch and those indices resolve against one
                    // shared cache, at which point every board on the home screen wears the first
                    // board's drawing. Measured, with a widget of two boards and thumbnails being
                    // rendered underneath it: five duplicated screens in six with a bitmap, none in
                    // six with an Icon, which carries its pixels with the row and has no index to
                    // resolve. WidgetUpdater's coalescing narrows the same race from the other end.
                    views.setImageViewIcon(R.id.widget_whiteboard_thumbnail,
                            Icon.createWithBitmap(thumbnail));
                } else {
                    views.setImageViewResource(R.id.widget_whiteboard_thumbnail, R.drawable.ic_whiteboard);
                }

                Intent fillIn = new Intent();
                fillIn.putExtra(DeepLinkRouter.EXTRA_OPEN_WHITEBOARD_ID, board.id);
                views.setOnClickFillInIntent(R.id.widget_item_root, fillIn);
                return views;
            } catch (RuntimeException e) {
                Log.e(TAG, "getViewAt(" + position + ") failed, rendering a blank row", e);
                return new RemoteViews(context.getPackageName(), R.layout.widget_whiteboard_item);
            }
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }

        @Override public long getItemId(int position) {
            try {
                return whiteboards.get(position).id.hashCode();
            } catch (RuntimeException e) {
                return position;
            }
        }

        @Override public boolean hasStableIds() { return true; }
    }
}
