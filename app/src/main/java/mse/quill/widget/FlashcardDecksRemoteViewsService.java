package mse.quill.widget;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

import mse.quill.MainActivity;
import mse.quill.R;
import mse.quill.data.FlashcardRepository;
import mse.quill.data.model.FlashcardDeck;
import mse.quill.util.NoteDisplayUtils;

public class FlashcardDecksRemoteViewsService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(this);
    }

    private static class Factory implements RemoteViewsFactory {
        private final Context context;
        private List<FlashcardDeck> decks = new ArrayList<>();

        Factory(Context context) {
            this.context = context;
        }

        @Override public void onCreate() {}

        @Override public void onDataSetChanged() {
            decks = new FlashcardRepository(context).loadDecksSync();
        }

        @Override public void onDestroy() { decks = new ArrayList<>(); }

        @Override public int getCount() { return decks.size(); }

        @Override public RemoteViews getViewAt(int position) {
            FlashcardDeck deck = decks.get(position);
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_flashcard_deck_item);
            views.setTextViewText(R.id.widget_item_title,
                    NoteDisplayUtils.resolveTitle(context, deck.noteTitle, deck.noteCreatedAt));
            views.setTextViewText(R.id.widget_item_subtitle, deck.due + " due · " + deck.total + " total");

            Intent fillIn = new Intent();
            fillIn.putExtra(MainActivity.EXTRA_OPEN_FLASHCARD_NOTE_ID, deck.noteId);
            views.setOnClickFillInIntent(R.id.widget_item_root, fillIn);
            return views;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) { return decks.get(position).noteId.hashCode(); }
        @Override public boolean hasStableIds() { return true; }
    }
}
