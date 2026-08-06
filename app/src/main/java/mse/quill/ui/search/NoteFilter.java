package mse.quill.ui.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import mse.quill.R;
import mse.quill.data.model.Collection;
import mse.quill.data.model.Note;
import mse.quill.data.model.Tag;
import mse.quill.model.Whiteboard;

/**
 * What the search bar is currently asking for, and the one place that answers it.
 *
 * <p>Held by the screen rather than by {@link SearchFilterBar}: Home and a collection both filter
 * their own lists, and the bar is a control, not a source of truth. Filtering runs in memory
 * because both screens already hold every note they display — pushing it into SQL would mean a
 * round trip per keystroke for lists this size.
 */
public final class NoteFilter {

    /** Ordering offered for notes. Collections follow the same intent where it applies. */
    public enum Sort {
        RECENT(R.string.sort_recent),
        OLDEST(R.string.sort_oldest),
        TITLE_ASC(R.string.sort_title_asc),
        TITLE_DESC(R.string.sort_title_desc);

        public final int labelRes;

        Sort(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    private String query = "";
    private Sort sort = Sort.RECENT;
    private final Set<String> tagIds = new HashSet<>();
    private boolean pinnedOnly;

    // ── State ──────────────────────────────────────────────────────────────

    public void setQuery(String raw) {
        query = raw == null ? "" : raw.trim().toLowerCase(Locale.getDefault());
    }

    public Sort sort() { return sort; }

    public void setSort(Sort sort) {
        this.sort = sort == null ? Sort.RECENT : sort;
    }

    public Set<String> tagIds() { return Collections.unmodifiableSet(tagIds); }

    public boolean hasTag(String tagId) { return tagIds.contains(tagId); }

    public void toggleTag(String tagId) {
        if (!tagIds.remove(tagId)) tagIds.add(tagId);
    }

    public void removeTag(String tagId) { tagIds.remove(tagId); }

    public boolean pinnedOnly() { return pinnedOnly; }

    public void setPinnedOnly(boolean pinnedOnly) { this.pinnedOnly = pinnedOnly; }

    /** Whether anything other than the default ordering is narrowing the list — which is what
     *  decides whether the bar shows its chip row at all. */
    public boolean isActive() {
        return !tagIds.isEmpty() || pinnedOnly || sort != Sort.RECENT;
    }

    public void clear() {
        tagIds.clear();
        pinnedOnly = false;
        sort = Sort.RECENT;
    }

    // ── Applying ───────────────────────────────────────────────────────────

    /** Notes matching the query, the selected tags and the pinned switch, in the chosen order. */
    public List<Note> apply(List<Note> notes) {
        List<Note> result = new ArrayList<>();
        for (Note note : notes) {
            if (pinnedOnly && note.pinnedAt == null) continue;
            if (!matchesTags(note)) continue;
            if (!matchesQuery(note)) continue;
            result.add(note);
        }
        sortNotes(result);
        return result;
    }

    /**
     * Collections matching the query, in the chosen order.
     *
     * <p>Tags and the pinned switch are deliberately ignored: they are properties of a note, and a
     * collection that vanished because none of its notes carried a tag would look like it had been
     * deleted. Sorting still applies, so the two lists don't disagree about what "oldest" means.
     */
    public List<Collection> applyToCollections(List<Collection> collections) {
        List<Collection> result = new ArrayList<>();
        for (Collection collection : collections) {
            if (query.isEmpty() || lower(collection.name).contains(query)) result.add(collection);
        }
        sortCollections(result);
        return result;
    }

    /**
     * Whiteboards matching the query, in the chosen order.
     *
     * <p>Tags and the pinned switch are ignored for the same reason collections ignore them: a
     * board can carry neither, so filtering by one would empty the section rather than narrow it.
     *
     * @param titleOf resolves a board's displayed name — boards are often untitled, and the
     *                fallback name is built from a Context this class deliberately doesn't hold.
     *                Matching the displayed title is what lets "untitled" find the unnamed ones.
     */
    public List<Whiteboard> applyToWhiteboards(List<Whiteboard> boards,
                                               Function<Whiteboard, String> titleOf) {
        List<Whiteboard> result = new ArrayList<>();
        for (Whiteboard board : boards) {
            if (query.isEmpty() || lower(titleOf.apply(board)).contains(query)) result.add(board);
        }
        sortWhiteboards(result, titleOf);
        return result;
    }

    private void sortWhiteboards(List<Whiteboard> boards, Function<Whiteboard, String> titleOf) {
        switch (sort) {
            case OLDEST:
                Collections.sort(boards, (a, b) -> Long.compare(a.updatedAt, b.updatedAt));
                break;
            case TITLE_ASC:
                Collections.sort(boards, (a, b) ->
                        lower(titleOf.apply(a)).compareTo(lower(titleOf.apply(b))));
                break;
            case TITLE_DESC:
                Collections.sort(boards, (a, b) ->
                        lower(titleOf.apply(b)).compareTo(lower(titleOf.apply(a))));
                break;
            case RECENT:
            default:
                Collections.sort(boards, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
                break;
        }
    }

    /** True when a tag filter is on and this note carries none of the selected tags. */
    private boolean matchesTags(Note note) {
        if (tagIds.isEmpty()) return true;
        if (note.tags == null) return false;
        // Any-of rather than all-of: picking two tags reads as "either of these", and all-of
        // would return nothing the moment a second tag is tapped.
        for (Tag tag : note.tags) {
            if (tagIds.contains(tag.id)) return true;
        }
        return false;
    }

    private boolean matchesQuery(Note note) {
        if (query.isEmpty()) return true;
        return lower(note.title).contains(query) || lower(note.preview).contains(query);
    }

    private void sortNotes(List<Note> notes) {
        switch (sort) {
            case OLDEST:
                Collections.sort(notes, (a, b) -> Long.compare(a.updatedAt, b.updatedAt));
                break;
            case TITLE_ASC:
                Collections.sort(notes, (a, b) -> compareTitles(a, b));
                break;
            case TITLE_DESC:
                Collections.sort(notes, (a, b) -> compareTitles(b, a));
                break;
            case RECENT:
            default:
                Collections.sort(notes, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
                break;
        }
    }

    /** Recency for a collection is {@code lastActivityAt} — the same value its card already shows
     *  as "Updated …", so the ordering matches what the user can read on it. */
    private void sortCollections(List<Collection> collections) {
        switch (sort) {
            case OLDEST:
                Collections.sort(collections, (a, b) -> Long.compare(a.lastActivityAt, b.lastActivityAt));
                break;
            case TITLE_ASC:
                Collections.sort(collections, (a, b) -> lower(a.name).compareTo(lower(b.name)));
                break;
            case TITLE_DESC:
                Collections.sort(collections, (a, b) -> lower(b.name).compareTo(lower(a.name)));
                break;
            case RECENT:
            default:
                Collections.sort(collections, (a, b) -> Long.compare(b.lastActivityAt, a.lastActivityAt));
                break;
        }
    }

    /** An untitled note sorts by the date it would be displayed under, not as an empty string —
     *  otherwise every unnamed note clumps at one end of an A–Z list. */
    private static int compareTitles(Note a, Note b) {
        String left = lower(a.title);
        String right = lower(b.title);
        if (left.isEmpty() && right.isEmpty()) return Long.compare(b.updatedAt, a.updatedAt);
        if (left.isEmpty()) return 1;
        if (right.isEmpty()) return -1;
        return left.compareTo(right);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.getDefault());
    }
}
