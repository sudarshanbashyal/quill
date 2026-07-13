package mse.quill.util;

import android.content.Context;
import android.graphics.Color;

import java.util.Random;

import mse.quill.R;

public final class ColorUtils {

    /** Shared pastel-tint ratio for collection cards, pinned note cards, etc. */
    public static final float PASTEL_CARD_WHITE_RATIO = 0.78f;

    private static final Random RANDOM = new Random();

    private ColorUtils() {}

    /** Blends {@code color} toward white by {@code whiteRatio} (0 = unchanged, 1 = white). */
    public static int lighten(int color, float whiteRatio) {
        int r = (int) (Color.red(color) + (255 - Color.red(color)) * whiteRatio);
        int g = (int) (Color.green(color) + (255 - Color.green(color)) * whiteRatio);
        int b = (int) (Color.blue(color) + (255 - Color.blue(color)) * whiteRatio);
        return Color.rgb(r, g, b);
    }

    /** Picks a random color from the shared swatch palette, e.g. for a newly created collection. */
    public static int randomPaletteColor(Context context) {
        int[] palette = context.getResources().getIntArray(R.array.swatch_color_palette);
        return palette[RANDOM.nextInt(palette.length)];
    }

    /** Deterministically picks a palette color for {@code id}, so the same item always gets the same tint. */
    public static int paletteColorForId(Context context, String id) {
        int[] palette = context.getResources().getIntArray(R.array.swatch_color_palette);
        int index = Math.floorMod(id.hashCode(), palette.length);
        return palette[index];
    }
}
