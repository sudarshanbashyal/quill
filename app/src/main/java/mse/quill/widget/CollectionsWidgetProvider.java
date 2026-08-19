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
 * Home-screen widget showing pinned notes and collections, stacked in one card — the launcher
 * equivalent of Home's pinned-notes strip and collections grid ({@code HomeFragment}). Two
 * {@code ListView}s in {@code widget_collections.xml}, each backed by its own
 * {@code RemoteViewsService} since a single service can only feed one collection view.
 */
public class CollectionsWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_collections);

            views.setRemoteAdapter(R.id.widget_pinned_notes_list,
                    new Intent(context, PinnedNotesRemoteViewsService.class));
            views.setEmptyView(R.id.widget_pinned_notes_list, R.id.widget_pinned_notes_empty);

            views.setRemoteAdapter(R.id.widget_collections_list,
                    new Intent(context, CollectionsRemoteViewsService.class));
            views.setEmptyView(R.id.widget_collections_list, R.id.widget_collections_empty);

            // A template rather than one PendingIntent per row: each row's fillInIntent (set in
            // the factories below) supplies the extra that tells MainActivity which item this
            // particular tap was. FLAG_MUTABLE, not FLAG_IMMUTABLE: the system has to merge each
            // row's fillInIntent into this template at click time, which an immutable
            // PendingIntent refuses — the click would still fire, but with none of the row's
            // extras, landing on Home instead of the item that was tapped.
            PendingIntent template = PendingIntent.getActivity(
                    context, 0, new Intent(context, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            views.setPendingIntentTemplate(R.id.widget_pinned_notes_list, template);
            views.setPendingIntentTemplate(R.id.widget_collections_list, template);

            manager.updateAppWidget(appWidgetId, views);
        }
    }
}
