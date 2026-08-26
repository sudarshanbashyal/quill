package mse.quill.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

import mse.quill.MainActivity;
import mse.quill.R;
import mse.quill.data.WhiteboardRepository;
import mse.quill.data.model.Whiteboard;
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

        private final Context context;
        private List<Whiteboard> whiteboards = new ArrayList<>();

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
                    views.setImageViewBitmap(R.id.widget_whiteboard_thumbnail, thumbnail);
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
