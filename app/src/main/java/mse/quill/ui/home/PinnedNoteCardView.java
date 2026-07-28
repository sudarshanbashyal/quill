package mse.quill.ui.home;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;

import mse.quill.R;
import mse.quill.data.model.Note;
import mse.quill.ui.tags.TagChipView;
import mse.quill.util.ColorUtils;
import mse.quill.util.NoteDisplayUtils;

/**
 * Builds a single pastel pinned-note card, added directly (not via RecyclerView) into
 * fragment_home.xml's pinned_cards_container — see NoteRowView for why views here are built in
 * code rather than via XML layout + LayoutInflater, and for the shared flat-card styling.
 */
final class PinnedNoteCardView {

    private PinnedNoteCardView() {}

    interface Listener {
        void onClicked(Note note);
        void onLongPressed(Note note);
    }

    static View build(Context context, Note note, Listener listener) {
        int width = dimen(context, R.dimen.pinned_card_width);
        int spacingXs = dimen(context, R.dimen.spacing_xs);
        int spacingSm = dimen(context, R.dimen.spacing_sm);
        int spacingMd = dimen(context, R.dimen.spacing_md);

        MaterialCardView card = new MaterialCardView(context);
        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMarginEnd(spacingSm);
        card.setLayoutParams(cardParams);
        NoteRowView.applyFlatCardStyle(card, R.dimen.card_corner_radius);
        card.setCardBackgroundColor(ColorUtils.lighten(
                ColorUtils.paletteColorForId(context, note.id), ColorUtils.PASTEL_CARD_WHITE_RATIO));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.setPadding(spacingMd, spacingMd, spacingMd, spacingMd);
        card.addView(content);

        TextView title = new TextView(context);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setText(NoteDisplayUtils.resolveTitle(context, note));
        content.addView(title);

        TextView date = new TextView(context);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateParams.topMargin = spacingXs;
        date.setLayoutParams(dateParams);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        date.setAlpha(0.7f);
        date.setText(DateUtils.getRelativeTimeSpanString(
                note.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        content.addView(date);

        LinearLayout tagsContainer = new LinearLayout(context);
        LinearLayout.LayoutParams tagsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagsParams.topMargin = spacingXs;
        tagsContainer.setLayoutParams(tagsParams);
        tagsContainer.setOrientation(LinearLayout.HORIZONTAL);
        tagsContainer.setVisibility(View.GONE);
        content.addView(tagsContainer);
        TagChipView.render(context, tagsContainer, note.tags);

        card.setOnClickListener(v -> listener.onClicked(note));
        card.setOnLongClickListener(v -> {
            listener.onLongPressed(note);
            return true;
        });

        return card;
    }

    private static int dimen(Context context, int dimenRes) {
        return context.getResources().getDimensionPixelSize(dimenRes);
    }
}
