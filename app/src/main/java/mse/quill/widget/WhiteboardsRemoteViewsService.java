package mse.quill.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
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

public class WhiteboardsRemoteViewsService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(this);
    }

    private static class Factory implements RemoteViewsFactory {
        private final Context context;
        private List<Whiteboard> whiteboards = new ArrayList<>();

        Factory(Context context) {
            this.context = context;
        }

        @Override public void onCreate() {}

        @Override public void onDataSetChanged() {
            whiteboards = new WhiteboardRepository(context).loadWhiteboardsSync();
        }

        @Override public void onDestroy() { whiteboards = new ArrayList<>(); }

        @Override public int getCount() { return whiteboards.size(); }

        @Override public RemoteViews getViewAt(int position) {
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
            fillIn.putExtra(MainActivity.EXTRA_OPEN_WHITEBOARD_ID, board.id);
            views.setOnClickFillInIntent(R.id.widget_item_root, fillIn);
            return views;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) { return whiteboards.get(position).id.hashCode(); }
        @Override public boolean hasStableIds() { return true; }
    }
}
