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
import mse.quill.data.CollectionRepository;
import mse.quill.data.model.Collection;

public class CollectionsRemoteViewsService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(this);
    }

    private static class Factory implements RemoteViewsFactory {
        private static final String TAG = "CollectionsWidget";

        private final Context context;
        private List<Collection> collections = new ArrayList<>();

        Factory(Context context) {
            this.context = context;
        }

        @Override public void onCreate() {}

        // See PinnedNotesRemoteViewsService's onDataSetChanged for why every throwing branch is
        // caught here: this runs on a binder thread inside this app's own process, and an
        // uncaught exception here crashes the whole app, not just the widget.
        @Override public void onDataSetChanged() {
            try {
                List<Collection> all = new CollectionRepository(context).loadCollectionsSync();
                List<Collection> visible = new ArrayList<>();
                // A locked collection's whole point is that its contents don't show without
                // authenticating — a home-screen widget has no way to ask for that, so it stays off.
                for (Collection collection : all) {
                    if (!collection.biometricLocked) visible.add(collection);
                }
                collections = visible;
            } catch (RuntimeException e) {
                Log.e(TAG, "loadCollectionsSync failed, showing an empty list", e);
                collections = new ArrayList<>();
            }
        }

        @Override public void onDestroy() { collections = new ArrayList<>(); }

        @Override public int getCount() { return collections.size(); }

        @Override public RemoteViews getViewAt(int position) {
            try {
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
            } catch (RuntimeException e) {
                Log.e(TAG, "getViewAt(" + position + ") failed, rendering a blank row", e);
                return new RemoteViews(context.getPackageName(), R.layout.widget_collection_item);
            }
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }

        @Override public long getItemId(int position) {
            try {
                return collections.get(position).id.hashCode();
            } catch (RuntimeException e) {
                return position;
            }
        }

        @Override public boolean hasStableIds() { return true; }
    }
}
