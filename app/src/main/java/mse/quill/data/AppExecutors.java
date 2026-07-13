package mse.quill.data;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared single background thread for all DB/disk I/O, so concurrent repositories never
 * contend for the SQLite write lock, plus a main-thread poster for delivering callbacks.
 */
public final class AppExecutors {

    private static volatile AppExecutors instance;

    private final ExecutorService diskIO = Executors.newSingleThreadExecutor();
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    private AppExecutors() {}

    public static AppExecutors getInstance() {
        if (instance == null) {
            synchronized (AppExecutors.class) {
                if (instance == null) instance = new AppExecutors();
            }
        }
        return instance;
    }

    public void diskIO(Runnable runnable) {
        diskIO.execute(runnable);
    }

    public void mainThread(Runnable runnable) {
        mainThreadHandler.post(runnable);
    }
}
