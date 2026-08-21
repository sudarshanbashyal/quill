package mse.quill.ui.notes.editor;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import mse.quill.R;

/**
 * One item in the formatting toolbar: an icon with a small primary-coloured dot beneath it that
 * appears while the format is active.
 *
 * <p>The dot carries the active state instead of the tonal/filled button M3 would normally use for
 * a selected toggle. This bar sits directly against the keyboard, and a row of filled pills there
 * reads as a second keyboard rather than as part of the app — the whole point of the surrounding
 * styling is to keep the bar quiet. The dot is also why the item is a composite view rather than a
 * bare {@code MaterialButton}: the button has no way to stack an indicator under its icon.
 *
 * <p>The item as a whole is the touch target, not just the icon, so the visible glyph can be small
 * without making the control hard to hit. The dot stays {@code INVISIBLE} rather than {@code GONE}
 * when inactive so the icon never shifts as state changes.
 */
public class FormattingButtonView extends LinearLayout {

    private static final float DISABLED_ALPHA = 0.3f;

    private boolean available = true;

    private final ImageView icon;
    private final View activeDot;
    private final ColorStateList inactiveTint;
    private final ColorStateList activeTint;

    public FormattingButtonView(Context context, int iconRes, CharSequence contentDescription) {
        super(context);

        inactiveTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_secondary));
        activeTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.brand_purple));

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setClickable(true);
        setFocusable(true);
        setContentDescription(contentDescription);
        setForeground(borderlessRipple(context));

        int iconSize = getResources().getDimensionPixelSize(R.dimen.formatting_icon_size);
        icon = new ImageView(context);
        icon.setLayoutParams(new LayoutParams(iconSize, iconSize));
        icon.setImageResource(iconRes);
        ImageViewCompat.setImageTintList(icon, inactiveTint);
        addView(icon);

        int dotSize = getResources().getDimensionPixelSize(R.dimen.formatting_dot_size);
        LayoutParams dotParams = new LayoutParams(dotSize, dotSize);
        dotParams.topMargin = getResources().getDimensionPixelSize(R.dimen.formatting_dot_margin_top);
        activeDot = new View(context);
        activeDot.setLayoutParams(dotParams);
        activeDot.setBackgroundResource(R.drawable.formatting_active_dot);
        activeDot.setVisibility(INVISIBLE);
        addView(activeDot);
    }

    /** Unbounded ripple — a bounded one would draw a rectangle around each icon, reintroducing
     *  exactly the boxy per-button look this toolbar moved away from. */
    private static android.graphics.drawable.Drawable borderlessRipple(Context context) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, value, true);
        return ContextCompat.getDrawable(context, value.resourceId);
    }

    public void setActive(boolean active) {
        activeDot.setVisibility(active ? VISIBLE : INVISIBLE);
        ImageViewCompat.setImageTintList(icon, active ? activeTint : inactiveTint);
    }

    /**
     * Tints the icon like an active control but without lighting the dot — for the replica of this
     * bar drawn by the Q&amp;A hint dialog, where the accent means "this is the one I'm pointing at",
     * not "this format is currently on". The dot would say the second thing.
     */
    public void setHighlighted(boolean highlighted) {
        ImageViewCompat.setImageTintList(icon, highlighted ? activeTint : inactiveTint);
    }

    /**
     * Whether the focused field offers this format at all. An unavailable control is dimmed rather
     * than hidden — the row keeps a stable shape, so the toolbar doesn't reflow every time the
     * caret moves in or out of a Q&amp;A block.
     *
     * <p>It stays clickable, though, which is the point: a dimmed control that swallows taps leaves
     * the user pressing it harder. The toolbar routes those taps to a message saying why instead.
     */
    public void setAvailable(boolean available) {
        this.available = available;
        setAlpha(available ? 1f : DISABLED_ALPHA);
        if (!available) setActive(false);
    }

    public boolean isAvailable() {
        return available;
    }

    public void setIcon(int iconRes) {
        icon.setImageResource(iconRes);
    }
}
