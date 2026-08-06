package mse.quill.ui.audio;

import java.util.Locale;

/** Clip timings, formatted the one way — so the segment, the mini player and anything later all
 *  read the same rather than each rounding and padding to taste. */
public final class PlaybackTime {

    private PlaybackTime() {}

    /** {@code m:ss}. */
    public static String format(int ms) {
        int totalSeconds = Math.max(0, ms) / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    /** {@code m:ss / m:ss} — where the playhead is, against how long the clip runs. */
    public static String elapsedOfTotal(int positionMs, int durationMs) {
        return format(positionMs) + " / " + format(durationMs);
    }
}
