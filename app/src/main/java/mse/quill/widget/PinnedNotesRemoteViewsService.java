package mse.quill.widget;

import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

import mse.quill.MainActivity;
import mse.quill.R;
import mse.quill.data.NoteRepository;
import mse.quill.data.model.Note;
import mse.quill.DeepLinkRouter;

public class PinnedNotesRemoteViewsService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(this);
    }

    private static class Factory implements RemoteViewsFactory {
        private static final String TAG = "PinnedNotesWidget";

        private final android.content.Context context;
        private List<Note> notes = new ArrayList<>();

        Factory(android.content.Context context) {
            this.context = context;
        }

        @Override public void onCreate() {}

        // Runs off the main thread — the RemoteViewsService contract guarantees this, which is
        // what makes it safe to call the repository's *Sync method here. Unlike almost anywhere
        // else in a widget, that thread lives inside this app's own process: an exception here
        // that escapes doesn't just break the widget, it crashes the whole app. So every branch
        // that can throw (a decrypt racing a collection getting locked/unlocked, for one) is
        // caught here rather than trusted to behave.
        @Override public void onDataSetChanged() {
            try {
                // The *ForWidget* query, not loadPinnedNotesSync: a widget has no session of its
                // own and no way to ask for one, so it hides every locked collection's notes
                // rather than only the ones shut this session. Done in SQL rather than by
                // filtering the result here, which is what this used to do — the old way still
                // decrypted the titles of an open collection's notes on the way to throwing them
                // away, and the LIMIT on the pinned query counted rows nobody was going to see.
                notes = new NoteRepository(context).loadPinnedNotesForWidgetSync();
            } catch (RuntimeException e) {
                Log.e(TAG, "loadPinnedNotesForWidgetSync failed, showing an empty list", e);
                notes = new ArrayList<>();
            }
        }

        @Override public void onDestroy() { notes = new ArrayList<>(); }

        @Override public int getCount() { return notes.size(); }

        @Override public RemoteViews getViewAt(int position) {
            try {
                Note note = notes.get(position);
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_pinned_note_item);
                views.setTextViewText(R.id.widget_item_title,
                        note.title == null || note.title.isEmpty() ? "Untitled" : note.title);
                views.setTextViewText(R.id.widget_item_subtitle, note.preview == null ? "" : note.preview);

                Intent fillIn = new Intent();
                fillIn.putExtra(DeepLinkRouter.EXTRA_OPEN_NOTE_ID, note.id);
                views.setOnClickFillInIntent(R.id.widget_item_root, fillIn);
                return views;
            } catch (RuntimeException e) {
                Log.e(TAG, "getViewAt(" + position + ") failed, rendering a blank row", e);
                return new RemoteViews(context.getPackageName(), R.layout.widget_pinned_note_item);
            }
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }

        @Override public long getItemId(int position) {
            try {
                return notes.get(position).id.hashCode();
            } catch (RuntimeException e) {
                return position;
            }
        }

        @Override public boolean hasStableIds() { return true; }
    }
}
