package mse.quill.ui.home;

import android.content.Context;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import mse.quill.R;
import mse.quill.data.model.Collection;
import mse.quill.data.model.Note;
import mse.quill.util.ColorUtils;

/**
 * Single adapter driving the whole Home scroll area: a "Collections" section header, a 2-column
 * grid of collection cards (Uncategorized + real collections + "+ New collection"), a "Notes"
 * section header, then a flat list of notes (or an empty-state row).
 *
 * All item views are built programmatically (see {@link NoteRowView}, {@link CollectionCardView})
 * rather than via XML layout + LayoutInflater: on this SDK, the first XML-attribute-derived
 * LayoutParams resolved within any given LayoutInflater.inflate() call throws "You must supply a
 * layout_width attribute" — reproducible with a bare single-TextView layout, independent of
 * RecyclerView, GridLayoutManager, or item content. Constructing views with `new View(context)` +
 * explicit LayoutParams objects (as CollectionDialogs's color swatch picker already does
 * elsewhere in this codebase) sidesteps that codepath entirely.
 */
public class HomeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_SECTION_HEADER = 0;
    private static final int TYPE_COLLECTION_CARD = 1;
    private static final int TYPE_NOTE = 2;
    private static final int TYPE_EMPTY_NOTES = 3;

    private static final float PASTEL_WHITE_RATIO = 0.78f;

    public interface Listener {
        /** collectionId is null for the Uncategorized tile. */
        void onCollectionClicked(String collectionId, String displayName);
        void onCollectionLongPressed(Collection collection);
        void onAddCollectionClicked();
        void onNoteClicked(Note note);
        void onNoteLongPressed(Note note);
    }

    private final Listener listener;
    private List<Collection> collections = new ArrayList<>();
    private int uncategorizedCount = 0;
    private boolean showUncategorized = true;
    private List<Note> notes = new ArrayList<>();

    public HomeAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitCollections(List<Collection> collections, int uncategorizedCount, boolean showUncategorized) {
        this.collections = collections;
        this.uncategorizedCount = uncategorizedCount;
        this.showUncategorized = showUncategorized;
        notifyDataSetChanged();
    }

    public void submitNotes(List<Note> notes) {
        this.notes = notes;
        notifyDataSetChanged();
    }

    public GridLayoutManager.SpanSizeLookup spanSizeLookup() {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                return getItemViewType(position) == TYPE_COLLECTION_CARD ? 1 : 2;
            }
        };
    }

    // ── Position math ────────────────────────────────────────────────────

    private static final int POS_COLLECTIONS_HEADER = 0;

    private int collectionsCount() {
        return collections.size() + (showUncategorized ? 1 : 0) + 1; // + Add tile
    }

    private int collectionsStart() { return POS_COLLECTIONS_HEADER + 1; }

    private int notesHeaderPos() { return collectionsStart() + collectionsCount(); }

    private int notesStart() { return notesHeaderPos() + 1; }

    private int notesSectionCount() { return notes.isEmpty() ? 1 : notes.size(); }

    @Override
    public int getItemCount() {
        return notesStart() + notesSectionCount();
    }

    @Override
    public int getItemViewType(int position) {
        if (position == POS_COLLECTIONS_HEADER || position == notesHeaderPos()) return TYPE_SECTION_HEADER;
        if (position < notesHeaderPos()) return TYPE_COLLECTION_CARD;
        return notes.isEmpty() ? TYPE_EMPTY_NOTES : TYPE_NOTE;
    }

    // ── ViewHolder creation/binding ──────────────────────────────────────

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context context = parent.getContext();
        switch (viewType) {
            case TYPE_SECTION_HEADER:
                return new SectionHeaderViewHolder(buildSectionHeader(context));
            case TYPE_COLLECTION_CARD:
                return new CollectionCardViewHolder(CollectionCardView.build(context));
            case TYPE_EMPTY_NOTES:
                return new EmptyViewHolder(buildEmptyMessage(context));
            default:
                return new NoteRowViewHolder(NoteRowView.build(context));
        }
    }

    private static TextView buildSectionHeader(Context context) {
        TextView header = new TextView(context);
        header.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        int marginStart = NoteRowView.dimen(context, R.dimen.spacing_md);
        int marginTop = NoteRowView.dimen(context, R.dimen.spacing_md);
        ((RecyclerView.LayoutParams) header.getLayoutParams()).setMargins(marginStart, marginTop, 0, 0);
        header.setTextColor(context.getColor(R.color.text_primary));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
        return header;
    }

    private static TextView buildEmptyMessage(Context context) {
        TextView empty = new TextView(context);
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
        params.topMargin = NoteRowView.dimen(context, R.dimen.spacing_lg);
        empty.setLayoutParams(params);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(context.getColor(R.color.text_secondary));
        return empty;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        int type = getItemViewType(position);
        if (type == TYPE_SECTION_HEADER) {
            int titleRes = position == POS_COLLECTIONS_HEADER ? R.string.section_collections : R.string.section_notes;
            ((SectionHeaderViewHolder) holder).bind(titleRes);
        } else if (type == TYPE_COLLECTION_CARD) {
            int index = position - collectionsStart();
            bindCollectionCard((CollectionCardViewHolder) holder, index);
        } else if (type == TYPE_EMPTY_NOTES) {
            ((EmptyViewHolder) holder).bind(R.string.empty_notes);
        } else {
            Note note = notes.get(position - notesStart());
            ((NoteRowViewHolder) holder).bind(note, listener);
        }
    }

    private void bindCollectionCard(CollectionCardViewHolder holder, int index) {
        if (showUncategorized && index == 0) {
            holder.bindUncategorized(uncategorizedCount, listener);
        } else if (index == collectionsCount() - 1) {
            holder.bindAdd(listener);
        } else {
            int collectionIndex = showUncategorized ? index - 1 : index;
            holder.bindCollection(collections.get(collectionIndex), listener);
        }
    }

    // ── ViewHolders ──────────────────────────────────────────────────────

    static class SectionHeaderViewHolder extends RecyclerView.ViewHolder {
        SectionHeaderViewHolder(@NonNull View itemView) { super(itemView); }
        void bind(int titleRes) { ((TextView) itemView).setText(titleRes); }
    }

    static class EmptyViewHolder extends RecyclerView.ViewHolder {
        EmptyViewHolder(@NonNull View itemView) { super(itemView); }
        void bind(int textRes) { ((TextView) itemView).setText(textRes); }
    }

    static class NoteRowViewHolder extends RecyclerView.ViewHolder {
        private final TextView titleView;
        private final TextView timestampView;

        NoteRowViewHolder(@NonNull NoteRowView.Views views) {
            super(views.root);
            titleView = views.titleView;
            timestampView = views.timestampView;
        }

        void bind(Note note, Listener listener) {
            boolean hasTitle = note.title != null && !note.title.trim().isEmpty();
            boolean hasPreview = note.preview != null && !note.preview.trim().isEmpty();
            titleView.setText(hasTitle ? note.title.trim()
                    : hasPreview ? note.preview.trim() : itemView.getContext().getString(R.string.untitled_note));
            timestampView.setText(DateUtils.getRelativeTimeSpanString(
                    note.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));

            itemView.setOnClickListener(v -> listener.onNoteClicked(note));
            itemView.setOnLongClickListener(v -> {
                listener.onNoteLongPressed(note);
                return true;
            });
        }
    }

    static class CollectionCardViewHolder extends RecyclerView.ViewHolder {
        private final android.graphics.drawable.GradientDrawable cardBackground;
        private final View detailGroup;
        private final TextView nameView;
        private final TextView countView;
        private final TextView updatedView;
        private final TextView addLabelView;

        CollectionCardViewHolder(@NonNull CollectionCardView.Views views) {
            super(views.root);
            cardBackground = views.background;
            detailGroup = views.detailGroup;
            nameView = views.nameView;
            countView = views.countView;
            updatedView = views.updatedView;
            addLabelView = views.addLabelView;
        }

        void bindCollection(Collection collection, Listener listener) {
            showDetailViews(true);
            nameView.setText(collection.name);
            countView.setText(formatCount(itemView, collection.noteCount));
            updatedView.setText(itemView.getContext().getString(
                    R.string.updated_relative_format,
                    DateUtils.getRelativeTimeSpanString(
                            collection.lastActivityAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)));
            cardBackground.setColor(ColorUtils.lighten(collection.color, PASTEL_WHITE_RATIO));

            itemView.setOnClickListener(v -> listener.onCollectionClicked(collection.id, collection.name));
            itemView.setOnLongClickListener(v -> {
                listener.onCollectionLongPressed(collection);
                return true;
            });
        }

        void bindUncategorized(int count, Listener listener) {
            showDetailViews(true);
            String name = itemView.getContext().getString(R.string.uncategorized);
            nameView.setText(name);
            countView.setText(formatCount(itemView, count));
            updatedView.setText("");
            cardBackground.setColor(itemView.getContext().getColor(R.color.uncategorized_card));

            itemView.setOnClickListener(v -> listener.onCollectionClicked(null, name));
            itemView.setOnLongClickListener(null);
        }

        void bindAdd(Listener listener) {
            showDetailViews(false);
            cardBackground.setColor(itemView.getContext().getColor(R.color.add_tile_background));

            itemView.setOnClickListener(v -> listener.onAddCollectionClicked());
            itemView.setOnLongClickListener(null);
        }

        private void showDetailViews(boolean show) {
            detailGroup.setVisibility(show ? View.VISIBLE : View.GONE);
            addLabelView.setVisibility(show ? View.GONE : View.VISIBLE);
        }

        private static String formatCount(View itemView, int count) {
            return count == 0
                    ? itemView.getContext().getString(R.string.notes_count_zero)
                    : itemView.getContext().getResources().getQuantityString(R.plurals.notes_count, count, count);
        }
    }
}
