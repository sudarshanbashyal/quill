package mse.quill.widget;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;

import mse.quill.R;

/**
 * Pushes a refresh to the home-screen widgets whenever the data they show changes, so a pin, a
 * rename, or a new whiteboard shows up without the user having to wait for the launcher's own
 * (much coarser) update cycle.
 *
 * <p>Every call is a no-op if the widget isn't on the home screen — {@link
 * AppWidgetManager#getAppWidgetIds} answers an empty array rather than throwing — and a no-op if
 * {@code context} is null, which happens for repository instances built without one (see
 * {@code WhiteboardRepository}'s database-only constructor).
 */
public final class WidgetUpdater {

    private WidgetUpdater() {}

    public static void notifyCollectionsChanged(Context context) {
        if (context == null) return;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(
                new ComponentName(context, CollectionsWidgetProvider.class));
        if (ids.length == 0) return;
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_pinned_notes_list);
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_collections_list);
    }

    public static void notifyWhiteboardsChanged(Context context) {
        if (context == null) return;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(
                new ComponentName(context, WhiteboardsWidgetProvider.class));
        if (ids.length == 0) return;
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_whiteboards_grid);
    }
}
