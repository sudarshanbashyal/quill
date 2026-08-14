package mse.quill.widget;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

import mse.quill.MainActivity;
import mse.quill.R;
import mse.quill.data.CollectionRepository;
import mse.quill.data.model.Collection;

public class CollectionsRemoteViewsService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(this);
    }

    private static class Factory implements RemoteViewsFactory {
        private final Context context;
        private List<Collection> collections = new ArrayList<>();

        Factory(Context context) {
            this.context = context;
        }

        @Override public void onCreate() {}

        @Override public void onDataSetChanged() {
            List<Collection> all = new CollectionRepository(context).loadCollectionsSync();
            List<Collection> visible = new ArrayList<>();
            // A locked collection's whole point is that its contents don't show without
            // authenticating — a home-screen widget has no way to ask for that, so it stays off.
            for (Collection collection : all) {
                if (!collection.biometricLocked) visible.add(collection);
            }
            collections = visible;
        }

        @Override public void onDestroy() { collections = new ArrayList<>(); }

        @Override public int getCount() { return collections.size(); }

        @Override public RemoteViews getViewAt(int position) {
            Collection collection = collections.get(position);
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_collection_item);
            views.setTextViewText(R.id.widget_item_title, collection.name);
            views.setTextViewText(R.id.widget_item_subtitle,
                    collection.noteCount + (collection.noteCount == 1 ? " note" : " notes"));

            Intent fillIn = new Intent();
            fillIn.putExtra(MainActivity.EXTRA_OPEN_COLLECTION_ID, collection.id);
            fillIn.putExtra(MainActivity.EXTRA_OPEN_COLLECTION_NAME, collection.name);
            views.setOnClickFillInIntent(R.id.widget_item_root, fillIn);
            return views;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) { return collections.get(position).id.hashCode(); }
        @Override public boolean hasStableIds() { return true; }
    }
}
