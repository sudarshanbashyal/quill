package mse.quill.widget;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

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
 *
 * <p>Every refresh is coalesced — see {@link #COALESCE_MS}. Changes arrive in bursts (Home drawing
 * a screenful of whiteboard thumbnails is one render, one refresh, per board, all within a few
 * milliseconds of each other) and a burst is what the launcher's collection adapter mishandles:
 * asked to reload while it is still fetching, it can leave one row's views in another row's slot,
 * which on the whiteboards widget shows up as two different boards wearing the same thumbnail.
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

    /**
     * How long a burst of changes is given to settle before the widgets are told, once.
     *
     * <p>Long enough to swallow a screenful of thumbnail renders arriving one after another, short
     * enough that a single edit still lands on the home screen while the user is looking at it.
     */
    private static final long COALESCE_MS = 200L;

    private static final Handler COALESCER = new Handler(Looper.getMainLooper());

    /** Always the one application context; kept so the posted refresh has something to run with. */
    private static volatile Context appContext;

    private static final Runnable PUSH_COLLECTIONS = () -> pushCollections(appContext);
    private static final Runnable PUSH_WHITEBOARDS = () -> pushWhiteboards(appContext);
    private static final Runnable PUSH_FLASHCARDS = () -> pushFlashcards(appContext);

    /**
     * Replaces any refresh of this kind already waiting, so a burst of changes costs one refresh
     * at the end rather than one apiece.
     */
    private static void schedule(Context context, Runnable push) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        COALESCER.removeCallbacks(push);
        COALESCER.postDelayed(push, COALESCE_MS);
    }

    public static void notifyCollectionsChanged(Context context) {
        schedule(context, PUSH_COLLECTIONS);
    }

    public static void notifyWhiteboardsChanged(Context context) {
        schedule(context, PUSH_WHITEBOARDS);
    }

    public static void notifyFlashcardsChanged(Context context) {
        schedule(context, PUSH_FLASHCARDS);
    }

    private static void pushCollections(Context context) {
        if (context == null) return;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(
                new ComponentName(context, CollectionsWidgetProvider.class));
        if (ids.length == 0) return;
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_pinned_notes_list);
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_collections_list);
    }

    private static void pushWhiteboards(Context context) {
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

    private static void pushFlashcards(Context context) {
        if (context == null) return;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(
                new ComponentName(context, FlashcardsWidgetProvider.class));
        if (ids.length == 0) return;
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_due_cards_list);
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_flashcard_decks_list);
    }
}
