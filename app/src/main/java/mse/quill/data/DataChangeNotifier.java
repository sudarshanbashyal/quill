package mse.quill.data;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * How the data layer says something changed, without knowing who cares.
 *
 * <p>Repositories used to call {@code WidgetUpdater.notifyXChanged(appContext)} inline — eight
 * times in {@code NoteRepository} alone, and in five other repositories besides, always written
 * fully qualified rather than imported, which is the tell that it was bolted on rather than
 * designed. That had {@code data/} knowing home-screen widgets exist, dragged the widget stack
 * into any repository test, and scattered "what must be refreshed after a write" across six files
 * — so the seventh repository added tomorrow would simply forget.
 *
 * <p>Now a repository announces <em>what changed</em> and stops there. {@code WidgetUpdater}
 * subscribes once, from {@code QuillApplication.onCreate}, and decides for itself which of its
 * widgets that means. Anything else that wants to know can subscribe the same way.
 *
 * <p><b>Threading.</b> Listeners are called on whatever thread wrote — in practice
 * {@link AppExecutors}' single disk thread — and are expected to be quick and thread-safe.
 * Nothing here hops to the main thread; a listener that needs to be there posts for itself.
 *
 * <p>The rule this exists to keep: <b>{@code data/} may not import {@code ui/} or
 * {@code widget/}.</b>
 */
public final class DataChangeNotifier {

    /** What changed, in the data layer's own terms — not "which widget to refresh". */
    public enum Change {
        NOTES,
        COLLECTIONS,
        WHITEBOARDS,
        FLASHCARDS,
        /** A change that is not one list's business — locking or unlocking a collection touches
         *  notes, boards and decks together, and so does the app re-locking on the way out. */
        EVERYTHING
    }

    public interface Listener {
        void onDataChanged(Change what);
    }

    private static final DataChangeNotifier INSTANCE = new DataChangeNotifier();

    /** Copy-on-write because listeners are added from the main thread and iterated from the disk
     *  thread; the lists are two or three entries long, so the copying costs nothing. */
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private DataChangeNotifier() {}

    public static DataChangeNotifier getInstance() {
        return INSTANCE;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void notifyChanged(Change what) {
        for (Listener listener : listeners) listener.onDataChanged(what);
    }
}
