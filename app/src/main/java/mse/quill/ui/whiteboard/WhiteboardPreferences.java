package mse.quill.ui.whiteboard;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The paper a new whiteboard starts on: whichever was chosen last.
 *
 * <p>A preference rather than a per-board default, because picking your paper says how you like to
 * work, not something about one board. Boards that already exist keep their own — changing the
 * preference must not repaper old notes.
 *
 * <p>Shared because boards are created from two places: Home's FAB (via
 * {@code WhiteboardRepository}) and {@code WhiteboardFragment} when it opens without an id. Putting
 * the preference here rather than in either one is what stops the two paths drifting apart — the
 * first version only taught the fragment about it, so boards made from the FAB, which is the way
 * they are actually made, silently stayed white.
 */
public final class WhiteboardPreferences {

    private static final String PREFS_NAME = "whiteboard_prefs";
    private static final String KEY_BACKGROUND = "default_background";

    private WhiteboardPreferences() {}

    /**
     * Dots, for a board nobody has expressed a preference about yet.
     *
     * <p>Blank white is the honest default in the sense that it adds nothing, and the wrong one in
     * the sense that it also gives nothing: dots are a sense of scale and a line to write along
     * without being ruled paper that insists on horizontal text. Only the <em>fallback</em> moved —
     * anyone who has already picked a paper keeps it, and boards that exist keep the one they were
     * made with.
     */
    public static int defaultBackground(Context context) {
        return prefs(context).getInt(KEY_BACKGROUND, WhiteboardView.BACKGROUND_DOTS);
    }

    public static void setDefaultBackground(Context context, int style) {
        prefs(context).edit().putInt(KEY_BACKGROUND, style).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** The preference file, so a full wipe can clear it along with everything else. */
    public static String prefsName() {
        return PREFS_NAME;
    }
}
