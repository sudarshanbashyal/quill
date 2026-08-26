package mse.quill.widget;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;

import mse.quill.R;
import mse.quill.data.DataChangeNotifier;

/**
 * Pushes a refresh to the home-screen widgets whenever the data they show changes, so a pin, a
 * rename, or a new whiteboard shows up without the user having to wait for the launcher's own
 * (much coarser) update cycle.
 *
 * <p>Every call is a no-op if the widget isn't on the home screen — {@link
 * AppWidgetManager#getAppWidgetIds} answers an empty array rather than throwing — and a no-op if
 * {@code context} is null.
 *
 * <p>Which widget a change means is decided <em>here</em>, in {@link #listenForDataChanges}, not
 * by the repository doing the writing. The data layer says "notes changed"; translating that into
 * "the collections widget shows pinned notes, so refresh it" is this class's business and nobody
 * else's.
 */
public final class WidgetUpdater {

    private WidgetUpdater() {}

    /** Guards against a second subscription if the process somehow runs onCreate twice. */
    private static boolean listening;

    /**
     * Subscribes the widgets to the data layer, once per process — see
     * {@code QuillApplication.onCreate}.
     *
     * <p>Holds the application context, which is what makes the inversion work: before this, every
     * repository had to be handed a Context purely so it could pass one back to this class, and
     * the ones built without one (whiteboards opened from the board screen) silently skipped the
     * refresh entirely.
     */
    public static void listenForDataChanges(Context context) {
        if (listening) return;
        listening = true;
        Context appContext = context.getApplicationContext();
        DataChangeNotifier.getInstance().addListener(what -> {
            switch (what) {
                // The collections widget shows pinned notes above the collections themselves, so
                // a note changing and a collection changing both land on it.
                case NOTES:
                case COLLECTIONS:
                    notifyCollectionsChanged(appContext);
                    break;
                case WHITEBOARDS:
                    notifyWhiteboardsChanged(appContext);
                    break;
                case FLASHCARDS:
                    notifyFlashcardsChanged(appContext);
                    break;
                case EVERYTHING:
                    notifyAllChanged(appContext);
                    break;
            }
        });
    }

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

    /**
     * Every widget at once, for a change that isn't one list's business — a collection's lock
     * being turned on or off touches notes, boards and decks together, and so does the session
     * ending when the app leaves the screen.
     */
    public static void notifyAllChanged(Context context) {
        notifyCollectionsChanged(context);
        notifyWhiteboardsChanged(context);
        notifyFlashcardsChanged(context);
    }

    public static void notifyFlashcardsChanged(Context context) {
        if (context == null) return;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(
                new ComponentName(context, FlashcardsWidgetProvider.class));
        if (ids.length == 0) return;
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_due_cards_list);
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_flashcard_decks_list);
    }
}
