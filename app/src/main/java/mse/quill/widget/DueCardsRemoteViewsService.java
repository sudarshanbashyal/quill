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
import mse.quill.data.serialization.MarkdownSerializer;

public class DueCardsRemoteViewsService extends RemoteViewsService {

    /** Fixed-height section like the collections widget's pinned-notes block — this is a nudge,
     *  not the full due queue, which the in-app review screen already covers. */
    private static final int LIMIT = 8;

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(this);
    }

    private static class Factory implements RemoteViewsFactory {
        private final Context context;
        private List<FlashcardRepository.DueCardPreview> cards = new ArrayList<>();

        Factory(Context context) {
            this.context = context;
        }

        @Override public void onCreate() {}

        @Override public void onDataSetChanged() {
            cards = new FlashcardRepository(context)
                    .loadDueCardsSync(System.currentTimeMillis(), LIMIT);
        }

        @Override public void onDestroy() { cards = new ArrayList<>(); }

        @Override public int getCount() { return cards.size(); }

        @Override public RemoteViews getViewAt(int position) {
            FlashcardRepository.DueCardPreview card = cards.get(position);
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_due_card_item);
            // front is Markdown (see DueCardPreview) — plain text is what a widget row can show.
            String front = MarkdownSerializer.fromMarkdown(card.front).toString();
            views.setTextViewText(R.id.widget_item_title, front.isEmpty() ? "Untitled card" : front);

            Intent fillIn = new Intent();
            fillIn.putExtra(MainActivity.EXTRA_OPEN_FLASHCARD_NOTE_ID, card.noteId);
            views.setOnClickFillInIntent(R.id.widget_item_root, fillIn);
            return views;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) { return cards.get(position).id.hashCode(); }
        @Override public boolean hasStableIds() { return true; }
    }
}
