package mse.quill.ui.home;

import android.content.Context;
import android.util.TypedValue;
import android.graphics.Outline;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import mse.quill.R;

/**
 * Builds a whiteboard-grid card in code — same reasons and same flat-card styling as
 * {@link CollectionCardView}; see {@link NoteRowView} for the inflater history behind it.
 *
 * A preview of the board, then its name and date — the shape the Figma HomePage_whiteboard frame
 * asks for. It previously showed a glyph badge and a stroke count, both standing in for the preview
 * that now exists (see {@link WhiteboardThumbnails} for what made it affordable).
 *
 * <p>Deviation from the frame, on purpose: the design has the image floating with no card behind
 * it. Home's collections and notes are all cards, so keeping the card and putting the preview
 * inside it holds that pattern together; the information the frame calls for — preview, name, date,
 * and no stroke count — is what changed.
 */
final class WhiteboardCardView {

    private WhiteboardCardView() {}

    static final class Views {
        final MaterialCardView root;
        final ImageView thumbnailView;
        final TextView titleView;
        final TextView updatedView;

        Views(MaterialCardView root, ImageView thumbnailView, TextView titleView,
              TextView updatedView) {
            this.root = root;
            this.thumbnailView = thumbnailView;
            this.titleView = titleView;
            this.updatedView = updatedView;
        }
    }

    static Views build(Context context) {
        int spacingSm = NoteRowView.dimen(context, R.dimen.spacing_sm);
        int spacingMd = NoteRowView.dimen(context, R.dimen.spacing_md);
        int minHeight = (int) (110 * context.getResources().getDisplayMetrics().density);

        MaterialCardView root = new MaterialCardView(context);
        RecyclerView.LayoutParams rootParams = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
        rootParams.setMargins(spacingSm, spacingSm, spacingSm, spacingSm);
        root.setLayoutParams(rootParams);
        root.setMinimumHeight(minHeight);
        NoteRowView.applyFlatCardStyle(root, R.dimen.card_corner_radius);
        root.setCardBackgroundColor(context.getColor(R.color.surface_container));

        LinearLayout detailGroup = new LinearLayout(context);
        detailGroup.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        detailGroup.setOrientation(LinearLayout.VERTICAL);
        root.addView(detailGroup);

        // Edge to edge across the top of the card, cropped to fill: a preview is a glance at the
        // drawing, not a diagram to read, so filling the space beats letterboxing it.
        ImageView thumbnail = new ImageView(context);
        thumbnail.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                NoteRowView.dimen(context, R.dimen.whiteboard_thumbnail_height)));
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackgroundColor(context.getColor(R.color.white));
        // Rounds the preview's top corners to match the card, leaving its bottom edge square where
        // it meets the title. The outline is a rounded rect pushed a radius past the bottom of the
        // view, so only its top two corners are inside — a plain rounded outline would curve the
        // join in the middle of the card, and the card's own outline doesn't clip its children.
        int radius = NoteRowView.dimen(context, R.dimen.card_corner_radius);
        thumbnail.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight() + radius, radius);
            }
        });
        thumbnail.setClipToOutline(true);
        detailGroup.addView(thumbnail);

        LinearLayout textGroup = new LinearLayout(context);
        textGroup.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        textGroup.setOrientation(LinearLayout.VERTICAL);
        textGroup.setPadding(spacingMd, spacingSm, spacingMd, spacingMd);
        detailGroup.addView(textGroup);

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.TOP);
        textGroup.addView(titleRow);

        TextView title = new TextView(context);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        titleRow.addView(title);

        TextView updated = new TextView(context);
        updated.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        updated.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        updated.setAlpha(0.7f);
        textGroup.addView(updated);

        return new Views(root, thumbnail, title, updated);
    }
}
