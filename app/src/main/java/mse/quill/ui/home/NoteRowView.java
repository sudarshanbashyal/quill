package mse.quill.ui.home;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import mse.quill.R;

/**
 * Builds a note row entirely in code rather than via an XML layout + LayoutInflater.
 *
 * On this SDK, the very first XML-attribute-derived LayoutParams resolved within a given
 * LayoutInflater.inflate() call throws "You must supply a layout_width attribute" — reproducible
 * with a bare single-TextView layout, independent of RecyclerView, GridLayoutManager, or item
 * content (see HomeAdapter's history/comments). Building views with `new View(context)` +
 * explicit LayoutParams objects (as CollectionDialogs's color swatch picker and
 * FormattingToolbarController already do successfully elsewhere in this codebase) sidesteps that
 * codepath entirely.
 */
final class NoteRowView {

    private NoteRowView() {}

    static final class Views {
        final View root;
        final TextView titleView;
        final TextView timestampView;
        final LinearLayout tagsContainer;

        Views(View root, TextView titleView, TextView timestampView, LinearLayout tagsContainer) {
            this.root = root;
            this.titleView = titleView;
            this.timestampView = timestampView;
            this.tagsContainer = tagsContainer;
        }
    }

    static Views build(Context context) {
        int spacingMd = dimen(context, R.dimen.spacing_md);
        int spacingSm = dimen(context, R.dimen.spacing_sm);
        int spacingXs = dimen(context, R.dimen.spacing_xs);
        int iconWidth = (int) (32 * context.getResources().getDisplayMetrics().density);

        LinearLayout outer = new LinearLayout(context);
        outer.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(spacingMd, spacingSm, spacingMd, spacingSm);
        outer.setClickable(true);
        outer.setFocusable(true);
        outer.setBackground(selectableItemBackground(context));

        LinearLayout row = new LinearLayout(context);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        outer.addView(row);

        TextView icon = new TextView(context);
        icon.setLayoutParams(new LinearLayout.LayoutParams(iconWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        icon.setGravity(Gravity.CENTER_HORIZONTAL);
        icon.setText(R.string.note_icon_glyph);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        row.addView(icon);

        TextView title = new TextView(context);
        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMarginEnd(spacingSm);
        title.setLayoutParams(titleParams);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(title);

        TextView timestamp = new TextView(context);
        timestamp.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        timestamp.setAlpha(0.6f);
        timestamp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(timestamp);

        LinearLayout tagsContainer = new LinearLayout(context);
        LinearLayout.LayoutParams tagsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagsParams.topMargin = spacingXs;
        tagsParams.setMarginStart(iconWidth);
        tagsContainer.setLayoutParams(tagsParams);
        tagsContainer.setOrientation(LinearLayout.HORIZONTAL);
        tagsContainer.setVisibility(View.GONE);
        outer.addView(tagsContainer);

        return new Views(outer, title, timestamp, tagsContainer);
    }

    static android.graphics.drawable.Drawable selectableItemBackground(Context context) {
        TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        return context.getDrawable(outValue.resourceId);
    }

    static int dimen(Context context, int dimenRes) {
        return context.getResources().getDimensionPixelSize(dimenRes);
    }
}
