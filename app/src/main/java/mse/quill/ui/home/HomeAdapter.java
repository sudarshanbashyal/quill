package mse.quill.ui.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
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
import mse.quill.util.RelativeTime;
import mse.quill.data.model.Collection;
import mse.quill.data.model.Note;
import mse.quill.data.model.Whiteboard;
import mse.quill.ui.tags.TagChipView;
import mse.quill.ui.whiteboard.WhiteboardThumbnails;
import mse.quill.util.NoteDisplayUtils;
import mse.quill.ui.common.CardStyles;

/**
 * Single adapter driving the Collections + Whiteboards + Notes scroll area below Home's
 * pinned-notes section and search bar: a "Collections" section header, a 2-column grid of
 * collection cards, a "Whiteboards" header and grid, then a "Notes" header and a flat list of
 * notes (each grid/list falling back to an empty-state row). Creating a new collection or
 * whiteboard is triggered from Home's expanding FAB, not from a card in these grids.
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
    private static final int TYPE_EMPTY = 3;
    private static final int TYPE_WHITEBOARD_CARD = 4;

    public interface Listener {
        /** A section header was tapped — every one of them offers to add to its own section, which
         *  is the only thing a header could usefully do and the shortest route to a first
         *  collection when the FAB is the only alternative. */
        void onCreateCollectionRequested();
        void onCreateNoteRequested();
        void onCreateWhiteboardRequested();

        void onCollectionClicked(String collectionId, String displayName);
        void onCollectionLongPressed(Collection collection);
        void onNoteClicked(Note note);
        void onNoteLongPressed(Note note);
        void onWhiteboardClicked(Whiteboard whiteboard);
        void onWhiteboardLongPressed(Whiteboard whiteboard);
    }

    private static final java.util.Random RANDOM = new java.util.Random();

    private final Listener listener;
    /** The line each empty section is currently showing, keyed by its array. See emptyMessage. */
    private final java.util.Map<Integer, String> emptyMessages = new java.util.HashMap<>();
    private List<Collection> collections = new ArrayList<>();
    private List<Whiteboard> whiteboards = new ArrayList<>();
    private List<Note> notes = new ArrayList<>();

    public HomeAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitCollections(List<Collection> collections) {
        emptyMessages.clear();
        this.collections = collections;
        notifyDataSetChanged();
    }

    public void submitWhiteboards(List<Whiteboard> whiteboards) {
        emptyMessages.clear();
        this.whiteboards = whiteboards;
        notifyDataSetChanged();
    }

    public void submitNotes(List<Note> notes) {
        emptyMessages.clear();
        this.notes = notes;
        notifyDataSetChanged();
    }

    public GridLayoutManager.SpanSizeLookup spanSizeLookup() {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                int type = getItemViewType(position);
                return type == TYPE_COLLECTION_CARD || type == TYPE_WHITEBOARD_CARD ? 1 : 2;
            }
        };
    }

    // ── Position math ────────────────────────────────────────────────────

    private static final int POS_COLLECTIONS_HEADER = 0;

    /** An empty section still occupies one row — the empty-state message. Collections were the
     *  one section without this, so a Quill with no collections showed a header with nothing under
     *  it, which reads as a section that failed to load rather than one waiting to be filled. */
    private int collectionsSectionCount() {
        return collections.isEmpty() ? 1 : collections.size();
    }

    private int collectionsStart() { return POS_COLLECTIONS_HEADER + 1; }

    private int notesHeaderPos() { return collectionsStart() + collectionsSectionCount(); }

    private int notesStart() { return notesHeaderPos() + 1; }

    private int notesSectionCount() { return notes.isEmpty() ? 1 : notes.size(); }

    private int whiteboardsHeaderPos() { return notesStart() + notesSectionCount(); }

    private int whiteboardsStart() { return whiteboardsHeaderPos() + 1; }

    private int whiteboardsSectionCount() { return whiteboards.isEmpty() ? 1 : whiteboards.size(); }

    /**
     * The note at a list position, or null if that position isn't a note row.
     *
     * <p>What the swipe handler asks, and the reason it can ask rather than being told: this list
     * interleaves headers, a grid of collection cards and a grid of whiteboard cards with the note
     * rows, and only the last of those is a full-width row that can slide away. Everything else
     * answers null and stays put.
     */
    public Note noteAt(int position) {
        if (getItemViewType(position) != TYPE_NOTE) return null;
        int index = position - notesStart();
        return index < 0 || index >= notes.size() ? null : notes.get(index);
    }

    @Override
    public int getItemCount() {
        return whiteboardsStart() + whiteboardsSectionCount();
    }

    @Override
    public int getItemViewType(int position) {
        if (position == POS_COLLECTIONS_HEADER
                || position == notesHeaderPos()
                || position == whiteboardsHeaderPos()) {
            return TYPE_SECTION_HEADER;
        }
        if (position < notesHeaderPos()) {
            return collections.isEmpty() ? TYPE_EMPTY : TYPE_COLLECTION_CARD;
        }
        if (position < whiteboardsHeaderPos()) return notes.isEmpty() ? TYPE_EMPTY : TYPE_NOTE;
        return whiteboards.isEmpty() ? TYPE_EMPTY : TYPE_WHITEBOARD_CARD;
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
            case TYPE_WHITEBOARD_CARD:
                return new WhiteboardCardViewHolder(WhiteboardCardView.build(context));
            case TYPE_EMPTY:
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
                CardStyles.dimen(context, R.dimen.list_item_gutter),
                CardStyles.dimen(context, R.dimen.section_header_margin_top),
                // Matching end margin, which the leading icon never needed: the trailing plus
                // would otherwise sit hard against the edge of the screen.
                CardStyles.dimen(context, R.dimen.list_item_gutter),
                CardStyles.dimen(context, R.dimen.section_header_margin_bottom));
        header.setLayoutParams(params);
        header.setBackground(rippleBackground(context));
        header.setPadding(0, CardStyles.dimen(context, R.dimen.spacing_xs),
                0, CardStyles.dimen(context, R.dimen.spacing_xs));
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setCompoundDrawablePadding(CardStyles.dimen(context, R.dimen.spacing_sm));
        header.setCompoundDrawableTintList(
                ColorStateList.valueOf(context.getColor(R.color.text_primary)));
        header.setTextColor(context.getColor(R.color.text_primary));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
        return header;
    }

    /** The platform's own selectable-item ripple, so a tappable header feels like every other
     *  tappable row rather than like text that happens to respond. */
    private static android.graphics.drawable.Drawable rippleBackground(Context context) {
        TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, outValue, true);
        return androidx.core.content.ContextCompat.getDrawable(context, outValue.resourceId);
    }

    private static TextView buildEmptyMessage(Context context) {
        TextView empty = new TextView(context);
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
        params.topMargin = CardStyles.dimen(context, R.dimen.spacing_lg);
        empty.setLayoutParams(params);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(context.getColor(R.color.text_secondary));
        return empty;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        int type = getItemViewType(position);
        if (type == TYPE_SECTION_HEADER) {
            ((SectionHeaderViewHolder) holder).bind(
                    sectionTitleRes(position), sectionIconRes(position), createActionFor(position));
        } else if (type == TYPE_COLLECTION_CARD) {
            int index = position - collectionsStart();
            bindCollectionCard((CollectionCardViewHolder) holder, index);
        } else if (type == TYPE_WHITEBOARD_CARD) {
            Whiteboard whiteboard = whiteboards.get(position - whiteboardsStart());
            ((WhiteboardCardViewHolder) holder).bind(whiteboard, listener);
        } else if (type == TYPE_EMPTY) {
            ((EmptyViewHolder) holder).bind(
                    emptyMessage(holder.itemView.getContext(), position));
        } else {
            Note note = notes.get(position - notesStart());
            ((NoteRowViewHolder) holder).bind(note, listener);
        }
    }

    /** Which section's empty row this is — the same three-way the header resources use, since an
     *  empty row only ever appears directly under its own header. */
    private int emptyMessageArrayRes(int position) {
        if (position < notesHeaderPos()) return R.array.empty_collections_lines;
        if (position < whiteboardsHeaderPos()) return R.array.empty_notes_lines;
        return R.array.empty_whiteboards_lines;
    }

    /**
     * One of that section's lines, held per section rather than drawn per bind.
     *
     * <p>A fresh line on every bind would reshuffle as the list scrolls or reloads, which reads as
     * a glitch rather than as variety — the same reason Home's greeting is picked once per visit.
     * Cleared whenever a section's contents change, so an emptied section can say something new
     * next time.
     */
    private String emptyMessage(Context context, int position) {
        int arrayRes = emptyMessageArrayRes(position);
        String held = emptyMessages.get(arrayRes);
        if (held != null) return held;

        String[] lines = context.getResources().getStringArray(arrayRes);
        String picked = lines[RANDOM.nextInt(lines.length)];
        emptyMessages.put(arrayRes, picked);
        return picked;
    }

    private void bindCollectionCard(CollectionCardViewHolder holder, int index) {
        holder.bindCollection(collections.get(index), listener);
    }

    private int sectionTitleRes(int position) {
        if (position == POS_COLLECTIONS_HEADER) return R.string.section_collections;
        if (position == notesHeaderPos()) return R.string.section_notes;
        return R.string.section_whiteboards;
    }

    /** What tapping this header does: add to the section it names. */
    private Runnable createActionFor(int position) {
        if (position == POS_COLLECTIONS_HEADER) return listener::onCreateCollectionRequested;
        if (position == notesHeaderPos()) return listener::onCreateNoteRequested;
        return listener::onCreateWhiteboardRequested;
    }

    /** Paired with {@link #sectionTitleRes}; every section header carries its icon. */
    private int sectionIconRes(int position) {
        if (position == POS_COLLECTIONS_HEADER) return R.drawable.ic_section_collection;
        if (position == notesHeaderPos()) return R.drawable.ic_section_note;
        return R.drawable.ic_section_whiteboard;
    }

    // ── ViewHolders ──────────────────────────────────────────────────────

    static class SectionHeaderViewHolder extends RecyclerView.ViewHolder {
        SectionHeaderViewHolder(@NonNull View itemView) { super(itemView); }

        void bind(int titleRes, int iconRes, Runnable onCreate) {
            TextView header = (TextView) itemView;
            header.setText(titleRes);
            // The icon resources are size-pinning layer-lists, so intrinsic bounds are already the
            // section-header icon size — see drawable/ic_section_note.xml. The trailing plus is
            // the same trick, and it is there because a header that does something has to look
            // like it does: the row is tappable across its full width, but nothing else on it
            // would say so.
            header.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    iconRes, 0, R.drawable.ic_section_add, 0);
            header.setOnClickListener(v -> onCreate.run());
        }
    }

    static class EmptyViewHolder extends RecyclerView.ViewHolder {
        EmptyViewHolder(@NonNull View itemView) { super(itemView); }
        void bind(String text) { ((TextView) itemView).setText(text); }
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
            timestampView.setText(RelativeTime.past(itemView.getContext(), note.updatedAt));
            TagChipView.render(itemView.getContext(), tagsContainer, note.tags);

            itemView.setOnClickListener(v -> listener.onNoteClicked(note));
            itemView.setOnLongClickListener(v -> {
                listener.onNoteLongPressed(note);
                return true;
            });
        }
    }

    static class WhiteboardCardViewHolder extends RecyclerView.ViewHolder {
        private final android.widget.ImageView thumbnailView;
        private final TextView titleView;
        private final TextView updatedView;

        WhiteboardCardViewHolder(@NonNull WhiteboardCardView.Views views) {
            super(views.root);
            thumbnailView = views.thumbnailView;
            titleView = views.titleView;
            updatedView = views.updatedView;
        }

        void bind(Whiteboard whiteboard, Listener listener) {
            Context context = itemView.getContext();
            bindThumbnail(context, whiteboard);
            titleView.setText(NoteDisplayUtils.resolveWhiteboardTitle(context, whiteboard));
            updatedView.setText(context.getString(
                    R.string.updated_relative_format,
                    RelativeTime.past(context, whiteboard.updatedAt)));

            itemView.setOnClickListener(v -> listener.onWhiteboardClicked(whiteboard));
            itemView.setOnLongClickListener(v -> {
                listener.onWhiteboardLongPressed(whiteboard);
                return true;
            });
        }

        /**
         * A preview arrives asynchronously, and this row may have been recycled onto a different
         * board by then — the tag says which board the pending render belongs to, so a slow one
         * can't paint itself onto somebody else's card.
         */
        private void bindThumbnail(Context context, Whiteboard whiteboard) {
            thumbnailView.setTag(whiteboard.id);
            thumbnailView.setImageDrawable(null);
            thumbnailView.setImageResource(R.drawable.ic_section_whiteboard);
            thumbnailView.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            WhiteboardThumbnails.load(context, whiteboard, thumbnail -> {
                if (!whiteboard.id.equals(thumbnailView.getTag())) return;
                if (thumbnail == null) return;   // nothing drawn yet: the placeholder stands
                thumbnailView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                thumbnailView.setImageBitmap(thumbnail);
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
            // A padlock ahead of the name, rather than a badge elsewhere on the card: it has to
            // read as a property of this collection at a glance, and the card is already carrying
            // a two-line contents summary and a timestamp. Cleared explicitly on the other branch
            // because view holders are recycled — without it, scrolling would smear the lock onto
            // whichever unlocked collection landed in this slot next.
            nameView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    collection.biometricLocked ? R.drawable.ic_lock_small : 0, 0, 0, 0);
            nameView.setCompoundDrawablePadding(
                    CardStyles.dimen(itemView.getContext(), R.dimen.spacing_sm));

            countView.setText(formatContents(itemView.getContext(), collection));
            updatedView.setText(itemView.getContext().getString(
                    R.string.updated_relative_format,
                    RelativeTime.past(itemView.getContext(), collection.lastActivityAt)));

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
