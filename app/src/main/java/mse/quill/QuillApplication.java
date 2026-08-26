package mse.quill;

import android.app.Application;

import mse.quill.widget.WidgetUpdater;

/**
 * The one place process-wide wiring happens.
 *
 * <p>Exists so {@link WidgetUpdater} can subscribe to {@link mse.quill.data.DataChangeNotifier}
 * once, at a point that is reached however the app woke up — a launcher tap, a widget's
 * {@code RemoteViewsService}, a Wear message, an alarm. Hanging that off {@code MainActivity}
 * instead would mean a write that happened without the activity ever starting left the widgets
 * showing yesterday's data.
 */
public class QuillApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        WidgetUpdater.listenForDataChanges(this);
    }
}
