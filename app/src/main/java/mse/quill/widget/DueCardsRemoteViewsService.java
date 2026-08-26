package mse.quill.widget;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

import mse.quill.MainActivity;
import mse.quill.R;
import mse.quill.data.FlashcardRepository;
import mse.quill.data.serialization.MarkdownSerializer;
import mse.quill.DeepLinkRouter;

public class DueCardsRemoteViewsService extends RemoteViewsService {

    /** Fixed-height section like the collections widget's pinned-notes block — this is a nudge,
     *  not the full due queue, which the in-app review screen already covers. */
    private static final int LIMIT = 8;

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(this);
    }

    private static class Factory implements RemoteViewsFactory {
        private static final String TAG = "DueCardsWidget";

        private final Context context;
        private List<FlashcardRepository.DueCardPreview> cards = new ArrayList<>();

        Factory(Context context) {
            this.context = context;
        }

        @Override public void onCreate() {}

        // See PinnedNotesRemoteViewsService's onDataSetChanged for why every throwing branch is
        // caught here: this runs on a binder thread inside this app's own process, and an
        // uncaught exception here crashes the whole app, not just the widget.
        @Override public void onDataSetChanged() {
            try {
                cards = new FlashcardRepository(context)
                        .loadDueCardsForWidgetSync(System.currentTimeMillis(), LIMIT);
            } catch (RuntimeException e) {
                Log.e(TAG, "loadDueCardsForWidgetSync failed, showing an empty list", e);
                cards = new ArrayList<>();
            }
        }

        @Override public void onDestroy() { cards = new ArrayList<>(); }

        @Override public int getCount() { return cards.size(); }

        @Override public RemoteViews getViewAt(int position) {
            try {
                FlashcardRepository.DueCardPreview card = cards.get(position);
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_due_card_item);
                // front is Markdown (see DueCardPreview) — plain text is what a widget row can show.
                String front = MarkdownSerializer.fromMarkdown(card.front).toString();
                views.setTextViewText(R.id.widget_item_title, front.isEmpty() ? "Untitled card" : front);

                Intent fillIn = new Intent();
                fillIn.putExtra(DeepLinkRouter.EXTRA_OPEN_FLASHCARD_NOTE_ID, card.noteId);
                views.setOnClickFillInIntent(R.id.widget_item_root, fillIn);
                return views;
            } catch (RuntimeException e) {
                Log.e(TAG, "getViewAt(" + position + ") failed, rendering a blank row", e);
                return new RemoteViews(context.getPackageName(), R.layout.widget_due_card_item);
            }
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }

        @Override public long getItemId(int position) {
            try {
                return cards.get(position).id.hashCode();
            } catch (RuntimeException e) {
                return position;
            }
        }

        @Override public boolean hasStableIds() { return true; }
    }
}
