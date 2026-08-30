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
import mse.quill.data.model.Whiteboard;

/**
 * What the search bar is currently asking for, and the one place that answers it.
 *
 * <p>Held by the screen rather than by {@link SearchFilterBar}: Home and a collection both filter
 * their own lists, and the bar is a control, not a source of truth. Tags and sorting run in memory,
 * because both screens already hold every note they display.
 *
 * <p>The text match does not. A note's body is only in memory as its <em>preview</em> — the first
 * line or two — so matching in memory could never find a word further down, which is most of what
 * a search box is for. The screen hands in {@code notes_fts}'s answer via
 * {@link #setFullTextMatches}, and the in-memory title match stays as the fallback for notes the
 * index doesn't have: one saved before the index existed, one in a collection that was locked and
 * has since been opened, or a device whose SQLite was built without FTS5.
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
    /** Ids the index matched, or null for "didn't ask / couldn't answer". */
    private Set<String> fullTextMatches;
    /** The query {@link #fullTextMatches} was computed for, so a slow answer to an older keystroke
     *  can be recognised as stale and ignored rather than filtering against the wrong word. */
    private String fullTextQuery;

    // ── State ──────────────────────────────────────────────────────────────

    public void setQuery(String raw) {
        query = raw == null ? "" : raw.trim().toLowerCase(Locale.getDefault());
        // The previous answer was about a different word. Dropped rather than kept, so a
        // half-typed query never filters against what was typed before it.
        if (!query.equals(fullTextQuery)) {
            fullTextMatches = null;
            fullTextQuery = null;
        }
    }

    /** The raw query as typed — what the screen hands to the index. */
    public String query() { return query; }

    /**
     * Records the index's answer for {@code forQuery}. A result for anything other than the
     * current query is discarded: keystrokes each start their own lookup and they can come back
     * out of order.
     */
    public void setFullTextMatches(String forQuery, Set<String> matches) {
        String normalised = forQuery == null ? "" : forQuery.trim().toLowerCase(Locale.getDefault());
        if (!normalised.equals(query)) return;
        fullTextQuery = normalised;
        fullTextMatches = matches;
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

    /** Whether anything other than the default ordering is narrowing the list — which is what
     *  decides whether the bar shows its chip row at all. */
    public boolean isActive() {
        return !tagIds.isEmpty() || sort != Sort.RECENT;
    }

    public void clear() {
        tagIds.clear();
        sort = Sort.RECENT;
    }

    // ── Applying ───────────────────────────────────────────────────────────

    /**
     * Notes matching the query and the selected tags, in the chosen order.
     *
     * <p>An untitled note is matched on its <em>displayed</em> name, the same way a board is: the
     * title column is empty on purpose, so matching that column alone left every unnamed note
     * unfindable by the one name the user can actually see on its row.
     *
     * <p>There was a "pinned only" switch here too. It was dropped because pinning is capped at
     * {@code NoteStore.MAX_PINNED_NOTES} — three — and those three already have a band of
     * their own at the top of Home. A filter that narrows a list to something permanently on
     * screen a few centimetres above it is a control with nothing to do.
     *
     * @param titleOf resolves a note's displayed name — the "Untitled Note - <date>" fallback is
     *                built from a Context this class deliberately doesn't hold.
     */
    public List<Note> apply(List<Note> notes, Function<Note, String> titleOf) {
        List<Note> result = new ArrayList<>();
        for (Note note : notes) {
            if (!matchesTags(note)) continue;
            if (!matchesQuery(note, titleOf.apply(note))) continue;
            result.add(note);
        }
        sortNotes(result);
        return result;
    }

    /**
     * Collections matching the query, in the chosen order.
     *
     * <p>Tags are deliberately ignored: they are a property of a note, and a collection that
     * vanished because none of its notes carried a tag would look like it had been deleted. Sorting still applies, so the two lists don't disagree about what "oldest" means.
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
     * <p>Tags are ignored for the same reason collections ignore them: a board cannot carry one,
     * so filtering by tag would empty the section rather than narrow it.
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

    private boolean matchesQuery(Note note, String displayedTitle) {
        if (query.isEmpty()) return true;
        // Title first, and always: it is the one field guaranteed to be in memory and current,
        // which is what makes an unindexed note still findable by name. The displayed one, so an
        // untitled note answers to the fallback name its row is showing.
        if (lower(displayedTitle).contains(query)) return true;
        if (fullTextMatches != null) return fullTextMatches.contains(note.id);
        return lower(note.preview).contains(query);
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
