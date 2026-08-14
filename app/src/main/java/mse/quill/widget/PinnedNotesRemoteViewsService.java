package mse.quill.widget;

import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

import mse.quill.MainActivity;
import mse.quill.R;
import mse.quill.data.NoteRepository;
import mse.quill.data.model.Note;

public class PinnedNotesRemoteViewsService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(this);
    }

    private static class Factory implements RemoteViewsFactory {
        private final android.content.Context context;
        private List<Note> notes = new ArrayList<>();

        Factory(android.content.Context context) {
            this.context = context;
        }

        @Override public void onCreate() {}

        // Runs off the main thread — the RemoteViewsService contract guarantees this, which is
        // what makes it safe to call the repository's *Sync method here.
        @Override public void onDataSetChanged() {
            notes = new NoteRepository(context).loadPinnedNotesSync();
        }

        @Override public void onDestroy() { notes = new ArrayList<>(); }

        @Override public int getCount() { return notes.size(); }

        @Override public RemoteViews getViewAt(int position) {
            Note note = notes.get(position);
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_pinned_note_item);
            views.setTextViewText(R.id.widget_item_title,
                    note.title == null || note.title.isEmpty() ? "Untitled" : note.title);
            views.setTextViewText(R.id.widget_item_subtitle, note.preview == null ? "" : note.preview);

            Intent fillIn = new Intent();
            fillIn.putExtra(MainActivity.EXTRA_OPEN_NOTE_ID, note.id);
            views.setOnClickFillInIntent(R.id.widget_item_root, fillIn);
            return views;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) { return notes.get(position).id.hashCode(); }
        @Override public boolean hasStableIds() { return true; }
    }
}
