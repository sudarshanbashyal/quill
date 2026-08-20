package mse.quill.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

import mse.quill.audio.AudioPlayback;
import mse.quill.data.AppDatabase;
import mse.quill.onboarding.Onboarding;
import mse.quill.security.AppLock;
import mse.quill.ui.profile.ProfilePreferences;
import mse.quill.ui.whiteboard.WhiteboardPreferences;

/**
 * Erases everything Quill has stored on this device. The Danger Zone's one button, and the only
 * caller there should ever be.
 *
 * <p>There is no undo and nothing to restore from: Quill keeps no server copy and no local
 * snapshot, so once this runs the notes are gone in the sense the word usually only pretends to
 * mean. The Profile screen says so in as many words before it calls this, and the confirmation is
 * deliberately not a one-tap affair.
 *
 * <p>Three stores have to go, not one. Dropping the database alone would leave every recording and
 * embedded image orphaned in {@code filesDir} — invisible, unreferenced, and still on disk, which
 * is the opposite of what the button promises. The preferences go too, so a wipe returns the app
 * to exactly its first-run state rather than to an empty notebook that still knows the user's name
 * and still has their lock armed.
 */
public final class DataWipe {

    private DataWipe() {}

    /**
     * Runs the wipe. Blocking and file-heavy, so it belongs on a background thread — see
     * {@link mse.quill.data.AppExecutors}.
     */
    public static void wipeEverything(Context context) {
        Context appContext = context.getApplicationContext();

        // Playback first. A clip playing from filesDir/audio survives its file being deleted (the
        // player already holds the descriptor), so it would keep sounding — and keep its
        // notification up — after the note it belongs to had ceased to exist.
        AudioPlayback playback = AudioPlayback.peek();
        if (playback != null) playback.close();

        AppDatabase.destroy(appContext);

        // Everything the app has written for itself: recordings, embedded images, and whatever a
        // half-finished import left in the cache. Both directories are Quill's alone, so clearing
        // them wholesale is safe and doesn't need a list of subdirectory names that would quietly
        // rot as features are added.
        deleteContents(appContext.getFilesDir());
        deleteContents(appContext.getCacheDir());

        clearPrefs(appContext, ProfilePreferences.prefsName());
        clearPrefs(appContext, AppLock.prefsName());
        clearPrefs(appContext, WhiteboardPreferences.prefsName());
        // So a wiped Quill is greeted the way a new one is. Without this the welcome screen stays
        // answered forever, and "delete everything" would leave behind the one thing the user
        // could not see to check: an empty app that has quietly decided it is not new.
        clearPrefs(appContext, Onboarding.prefsName());

        // The wiped lock preference only decides what happens next launch; this drops the
        // already-granted unlock so the process can't carry it forward.
        AppLock.lock();
    }

    /** Empties a directory but keeps the directory itself, which the framework owns. */
    private static void deleteContents(File dir) {
        if (dir == null) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            deleteRecursively(child);
        }
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        // Return value ignored on purpose: a file that can't be removed (held open by something
        // still shutting down) must not abort the rest of the wipe. Leaving one stray recording
        // behind is a far better outcome than stopping halfway with the database already gone.
        file.delete();
    }

    private static void clearPrefs(Context appContext, String name) {
        SharedPreferences prefs = appContext.getSharedPreferences(name, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }
}
