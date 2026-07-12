package mse.quill.util;

import android.graphics.Color;

public final class ColorUtils {

    private ColorUtils() {}

    /** Blends {@code color} toward white by {@code whiteRatio} (0 = unchanged, 1 = white). */
    public static int lighten(int color, float whiteRatio) {
        int r = (int) (Color.red(color) + (255 - Color.red(color)) * whiteRatio);
        int g = (int) (Color.green(color) + (255 - Color.green(color)) * whiteRatio);
        int b = (int) (Color.blue(color) + (255 - Color.blue(color)) * whiteRatio);
        return Color.rgb(r, g, b);
    }
}
