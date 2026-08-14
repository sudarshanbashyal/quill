package mse.quill.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import mse.quill.MainActivity;
import mse.quill.R;

/**
 * Home-screen widget showing recent whiteboards with thumbnails — the launcher equivalent of
 * Home's whiteboards grid ({@code WhiteboardCardView}). Thumbnails come from
 * {@link WidgetThumbnailCache}, not a live render: see that class for why.
 */
public class WhiteboardsWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_whiteboards);

            views.setRemoteAdapter(R.id.widget_whiteboards_grid,
                    new Intent(context, WhiteboardsRemoteViewsService.class));
            views.setEmptyView(R.id.widget_whiteboards_grid, R.id.widget_whiteboards_empty);

            // FLAG_MUTABLE: see CollectionsWidgetProvider's template for why an immutable one
            // would silently drop each row's fillInIntent extras.
            PendingIntent template = PendingIntent.getActivity(
                    context, 0, new Intent(context, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            views.setPendingIntentTemplate(R.id.widget_whiteboards_grid, template);

            manager.updateAppWidget(appWidgetId, views);
        }
    }
}
