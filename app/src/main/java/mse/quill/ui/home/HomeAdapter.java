package mse.quill.ui.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import mse.quill.R;
import mse.quill.data.model.Collection;
import mse.quill.data.model.Note;
import mse.quill.ui.tags.TagChipView;
import mse.quill.util.NoteDisplayUtils;

/**
 * Single adapter driving the Collections + Notes scroll area below Home's pinned-notes section
 * and search bar: a "Collections" section header, a 2-column grid of collection cards, a "Notes"
 * section header, then a flat list of notes (or an empty-state row). Creating a new collection is
 * triggered from Home's expanding FAB, not from a card in this grid.
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

    public interface Listener {
        void onCollectionClicked(String collectionId, String displayName);
        void onCollectionLongPressed(Collection collection);
        void onNoteClicked(Note note);
        void onNoteLongPressed(Note note);
    }

    private final Listener listener;
    private List<Collection> collections = new ArrayList<>();
    private List<Note> notes = new ArrayList<>();

    public HomeAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitCollections(List<Collection> collections) {
        this.collections = collections;
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
        return collections.size();
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
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
        params.setMargins(
                NoteRowView.dimen(context, R.dimen.list_item_gutter),
                NoteRowView.dimen(context, R.dimen.section_header_margin_top),
                0,
                NoteRowView.dimen(context, R.dimen.section_header_margin_bottom));
        header.setLayoutParams(params);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setCompoundDrawablePadding(NoteRowView.dimen(context, R.dimen.spacing_sm));
        header.setCompoundDrawableTintList(
                ColorStateList.valueOf(context.getColor(R.color.text_primary)));
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
            boolean collections = position == POS_COLLECTIONS_HEADER;
            ((SectionHeaderViewHolder) holder).bind(
                    collections ? R.string.section_collections : R.string.section_notes,
                    collections ? R.drawable.ic_section_collection : R.drawable.ic_section_note);
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
        holder.bindCollection(collections.get(index), listener);
    }

    // ── ViewHolders ──────────────────────────────────────────────────────

    static class SectionHeaderViewHolder extends RecyclerView.ViewHolder {
        SectionHeaderViewHolder(@NonNull View itemView) { super(itemView); }

        void bind(int titleRes, int iconRes) {
            TextView header = (TextView) itemView;
            header.setText(titleRes);
            // The icon resources are size-pinning layer-lists, so intrinsic bounds are already the
            // section-header icon size — see drawable/ic_section_note.xml.
            header.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
        }
    }

    static class EmptyViewHolder extends RecyclerView.ViewHolder {
        EmptyViewHolder(@NonNull View itemView) { super(itemView); }
        void bind(int textRes) { ((TextView) itemView).setText(textRes); }
    }

    static class NoteRowViewHolder extends RecyclerView.ViewHolder {
        private final TextView titleView;
        private final TextView timestampView;
        private final LinearLayout tagsContainer;

        NoteRowViewHolder(@NonNull NoteRowView.Views views) {
            super(views.root);
            titleView = views.titleView;
            timestampView = views.timestampView;
            tagsContainer = views.tagsContainer;
        }

        void bind(Note note, Listener listener) {
            titleView.setText(NoteDisplayUtils.resolveTitle(itemView.getContext(), note));
            timestampView.setText(DateUtils.getRelativeTimeSpanString(
                    note.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            TagChipView.render(itemView.getContext(), tagsContainer, note.tags);

            itemView.setOnClickListener(v -> listener.onNoteClicked(note));
            itemView.setOnLongClickListener(v -> {
                listener.onNoteLongPressed(note);
                return true;
            });
        }
    }

    static class CollectionCardViewHolder extends RecyclerView.ViewHolder {
        private final TextView nameView;
        private final TextView countView;
        private final TextView updatedView;

        CollectionCardViewHolder(@NonNull CollectionCardView.Views views) {
            super(views.root);
            nameView = views.nameView;
            countView = views.countView;
            updatedView = views.updatedView;
        }

        void bindCollection(Collection collection, Listener listener) {
            nameView.setText(collection.name);
            countView.setText(formatContents(itemView.getContext(), collection));
            updatedView.setText(itemView.getContext().getString(
                    R.string.updated_relative_format,
                    DateUtils.getRelativeTimeSpanString(
                            collection.lastActivityAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)));

            itemView.setOnClickListener(v -> listener.onCollectionClicked(collection.id, collection.name));
            itemView.setOnLongClickListener(v -> {
                listener.onCollectionLongPressed(collection);
                return true;
            });
        }

        /**
         * "12 notes · 30 flashcards · 2 quizzes". Notes are always stated, even at zero, because
         * that is the card's headline fact; flashcards and quizzes only appear once they exist, so
         * a plain collection of notes doesn't advertise two empty features.
         */
        private static String formatContents(Context context, Collection collection) {
            Resources res = context.getResources();
            StringBuilder summary = new StringBuilder(collection.noteCount == 0
                    ? context.getString(R.string.notes_count_zero)
                    : res.getQuantityString(R.plurals.notes_count, collection.noteCount, collection.noteCount));
            String separator = context.getString(R.string.count_separator);
            if (collection.flashcardCount > 0) {
                summary.append(separator).append(res.getQuantityString(
                        R.plurals.flashcards_count, collection.flashcardCount, collection.flashcardCount));
            }
            if (collection.quizCount > 0) {
                summary.append(separator).append(res.getQuantityString(
                        R.plurals.quizzes_count, collection.quizCount, collection.quizCount));
            }
            return summary.toString();
        }
    }
}
