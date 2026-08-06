package mse.quill.ui.home;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import mse.quill.R;
import mse.quill.data.CollectionRepository;
import mse.quill.data.NoteRepository;
import mse.quill.data.WhiteboardRepository;
import mse.quill.data.model.Collection;
import mse.quill.data.TagRepository;
import mse.quill.data.model.Note;
import mse.quill.data.model.Tag;
import mse.quill.model.Whiteboard;
import mse.quill.ui.collections.CollectionDetailFragment;
import mse.quill.ui.notes.NoteEditorFragment;
import mse.quill.ui.search.NoteFilter;
import mse.quill.ui.search.SearchFilterBar;
import mse.quill.ui.search.SearchFilterDialog;
import mse.quill.ui.whiteboard.WhiteboardFragment;
import mse.quill.util.ColorUtils;
import mse.quill.util.NoteDisplayUtils;
import mse.quill.util.WindowInsetsUtils;

public class HomeFragment extends Fragment implements WindowInsetsUtils.TopInsetHost {

    /** The gradient header, not the root: the root is a transparent {@code FrameLayout}, so the
     *  strip behind the status bar would show the window background rather than the gradient. */
    @Override
    public View topInsetTarget(View root) {
        return root.findViewById(R.id.home_header);
    }

    private NoteRepository noteRepository;
    private CollectionRepository collectionRepository;
    private WhiteboardRepository whiteboardRepository;

    private HomeAdapter homeAdapter;
    private View pinnedSection;
    private LinearLayout pinnedCardsContainer;

    private List<Collection> allCollections = new ArrayList<>();
    private List<Whiteboard> allWhiteboards = new ArrayList<>();
    private List<Note> allNotes = new ArrayList<>();
    private List<Tag> allTags = new ArrayList<>();
    /** Survives a reload; the list is re-derived from it rather than the other way round. */
    private final NoteFilter filter = new NoteFilter();
    private SearchFilterBar searchBar;
    private TagRepository tagRepository;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                              Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        noteRepository = new NoteRepository(requireContext());
        collectionRepository = new CollectionRepository(requireContext());
        tagRepository = new TagRepository(requireContext());
        whiteboardRepository = new WhiteboardRepository(requireContext());

        homeAdapter = new HomeAdapter(new HomeAdapter.Listener() {
            @Override public void onCollectionClicked(String collectionId, String displayName) {
                openCollection(collectionId, displayName);
            }

            @Override public void onCollectionLongPressed(Collection collection) {
                showManageCollectionDialog(collection);
            }

            @Override public void onNoteClicked(Note note) { openNote(note.id); }

            @Override public void onNoteLongPressed(Note note) {
                boolean isPinned = note.pinnedAt != null;
                CollectionDialogs.showNoteActionsDialog(requireContext(), allCollections, note.collectionId, isPinned,
                        collectionId -> noteRepository.assignCollection(note.id, collectionId, HomeFragment.this::reloadAll),
                        () -> togglePin(note, isPinned),
                        () -> noteRepository.deleteNote(note.id, HomeFragment.this::reloadAll));
            }

            @Override public void onWhiteboardClicked(Whiteboard whiteboard) {
                openWhiteboard(whiteboard.id, whiteboard.noteId);
            }

            @Override public void onWhiteboardLongPressed(Whiteboard whiteboard) {
                showManageWhiteboardDialog(whiteboard);
            }
        });

        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), 2);
        layoutManager.setSpanSizeLookup(homeAdapter.spanSizeLookup());

        RecyclerView recyclerView = view.findViewById(R.id.recycler_home);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(homeAdapter);

        pinnedSection = view.findViewById(R.id.pinned_section);
        pinnedCardsContainer = view.findViewById(R.id.pinned_cards_container);

        searchBar = view.findViewById(R.id.search_bar);
        searchBar.setListener(new SearchFilterBar.Listener() {
            @Override public void onQueryChanged(String query) {
                filter.setQuery(query);
                applyFilters();
            }

            @Override public void onFilterRequested() {
                SearchFilterDialog.show(requireContext(), filter, allTags, HomeFragment.this::onFilterChanged);
            }

            @Override public void onFilterCleared() {
                onFilterChanged();
            }
        });

        setupFabMenu(view);
    }

    private static final long FAB_OPTIONS_ANIM_DURATION_MS = 180;

    private void setupFabMenu(View view) {
        View fabOptions = view.findViewById(R.id.fab_options);
        View fabOptionNote = view.findViewById(R.id.fab_option_note);
        View fabOptionCollection = view.findViewById(R.id.fab_option_collection);
        View fabOptionWhiteboard = view.findViewById(R.id.fab_option_whiteboard);
        int sweepDistance = getResources().getDimensionPixelSize(R.dimen.fab_option_sweep_distance);

        view.findViewById(R.id.fab_new_note).setOnClickListener(v -> {
            boolean expanded = fabOptions.getVisibility() == View.VISIBLE;
            if (expanded) {
                collapseFabOptions(fabOptions, sweepDistance);
            } else {
                expandFabOptions(fabOptions, sweepDistance);
            }
        });

        fabOptionNote.setOnClickListener(v -> {
            collapseFabOptions(fabOptions, sweepDistance);
            NavHostFragment.findNavController(this).navigate(R.id.noteEditorFragment);
        });

        fabOptionCollection.setOnClickListener(v -> {
            collapseFabOptions(fabOptions, sweepDistance);
            CollectionDialogs.showCreateDialog(requireContext(), name ->
                    collectionRepository.createCollection(
                            name, ColorUtils.randomPaletteColor(requireContext()), id -> reloadCollections()));
        });

        // A board created here is standalone (no parent note) — it's owned by Home's Whiteboards
        // section. The row is inserted up front so the board exists in that list even if the user
        // backs out without drawing anything.
        fabOptionWhiteboard.setOnClickListener(v -> {
            collapseFabOptions(fabOptions, sweepDistance);
            WhiteboardDialogs.showCreateDialog(requireContext(), title ->
                    whiteboardRepository.createWhiteboard(
                            title.isEmpty() ? null : title, null,
                            whiteboardId -> openWhiteboard(whiteboardId, null)));
        });
    }

    /** Sweeps the option buttons up from behind the FAB while fading them in. */
    private void expandFabOptions(View fabOptions, int sweepDistance) {
        fabOptions.animate().cancel();
        fabOptions.setAlpha(0f);
        fabOptions.setTranslationY(sweepDistance);
        fabOptions.setVisibility(View.VISIBLE);
        fabOptions.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(FAB_OPTIONS_ANIM_DURATION_MS)
                .start();
    }

    /** Reverses the sweep — slides the option buttons back down behind the FAB while fading out. */
    private void collapseFabOptions(View fabOptions, int sweepDistance) {
        if (fabOptions.getVisibility() != View.VISIBLE) return;
        fabOptions.animate().cancel();
        fabOptions.animate()
                .alpha(0f)
                .translationY(sweepDistance)
                .setDuration(FAB_OPTIONS_ANIM_DURATION_MS)
                .withEndAction(() -> fabOptions.setVisibility(View.GONE))
                .start();
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadAll();
    }

    private void openNote(String noteId) {
        Bundle args = new Bundle();
        args.putString(NoteEditorFragment.ARG_NOTE_ID, noteId);
        NavHostFragment.findNavController(this).navigate(R.id.noteEditorFragment, args);
    }

    private void openCollection(String collectionId, String displayName) {
        Bundle args = new Bundle();
        args.putString(CollectionDetailFragment.ARG_COLLECTION_ID, collectionId);
        args.putString(CollectionDetailFragment.ARG_COLLECTION_NAME, displayName);
        NavHostFragment.findNavController(this).navigate(R.id.collectionDetailFragment, args);
    }

    private void openWhiteboard(String whiteboardId, String noteId) {
        if (!isAdded()) return;
        Bundle args = new Bundle();
        args.putString(WhiteboardFragment.ARG_WHITEBOARD_ID, whiteboardId);
        args.putString(WhiteboardFragment.ARG_NOTE_ID, noteId);
        NavHostFragment.findNavController(this).navigate(R.id.whiteboardFragment, args);
    }

    private void showManageWhiteboardDialog(Whiteboard whiteboard) {
        WhiteboardDialogs.showManageDialog(requireContext(), whiteboard, new WhiteboardDialogs.ManageListener() {
            @Override public void onRename() {
                WhiteboardDialogs.showRenameDialog(requireContext(), whiteboard.title, newTitle ->
                        whiteboardRepository.renameWhiteboard(
                                whiteboard.id, newTitle, HomeFragment.this::reloadWhiteboards));
            }

            @Override public void onDelete() {
                WhiteboardDialogs.showDeleteConfirmation(requireContext(), whiteboard, () ->
                        whiteboardRepository.deleteWhiteboard(
                                whiteboard.id, HomeFragment.this::reloadWhiteboards));
            }
        });
    }

    private void showManageCollectionDialog(Collection collection) {
        CollectionDialogs.showManageDialog(requireContext(), collection, new CollectionDialogs.ManageListener() {
            @Override public void onRename() {
                CollectionDialogs.showRenameDialog(requireContext(), collection.name, newName ->
                        collectionRepository.renameCollection(collection.id, newName, HomeFragment.this::reloadCollections));
            }

            @Override public void onDelete() {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.delete_collection_title_format, collection.name))
                        .setMessage(R.string.delete_collection_message)
                        .setPositiveButton(R.string.action_delete, (d, w) ->
                                collectionRepository.deleteCollection(collection.id, HomeFragment.this::reloadAll))
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            }
        });
    }

    private void togglePin(Note note, boolean isPinned) {
        if (isPinned) {
            noteRepository.unpinNote(note.id, this::reloadAll);
            return;
        }
        noteRepository.pinNote(note.id, new NoteRepository.OnPinResult() {
            @Override public void onPinned() { reloadAll(); }

            @Override public void onLimitReached() {
                if (isAdded()) {
                    Toast.makeText(requireContext(), R.string.pin_limit_reached_message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void reloadAll() {
        reloadCollections();
        reloadWhiteboards();
        reloadNotes();
        reloadPinnedNotes();
        reloadTags();
    }

    private void reloadWhiteboards() {
        whiteboardRepository.loadWhiteboards(whiteboards -> {
            if (!isAdded()) return;
            allWhiteboards = whiteboards;
            applyFilters();
        });
    }

    private void reloadPinnedNotes() {
        noteRepository.loadPinnedNotes(notes -> {
            if (!isAdded()) return;
            renderPinnedSection(notes);
        });
    }

    private void renderPinnedSection(List<Note> pinnedNotes) {
        pinnedCardsContainer.removeAllViews();
        pinnedSection.setVisibility(pinnedNotes.isEmpty() ? View.GONE : View.VISIBLE);

        PinnedNoteCardView.Listener listener = new PinnedNoteCardView.Listener() {
            @Override public void onClicked(Note note) { openNote(note.id); }

            @Override public void onLongPressed(Note note) {
                CollectionDialogs.showNoteActionsDialog(requireContext(), allCollections, note.collectionId, true,
                        collectionId -> noteRepository.assignCollection(note.id, collectionId, HomeFragment.this::reloadAll),
                        () -> togglePin(note, true),
                        () -> noteRepository.deleteNote(note.id, HomeFragment.this::reloadAll));
            }
        };

        for (Note note : pinnedNotes) {
            pinnedCardsContainer.addView(PinnedNoteCardView.build(requireContext(), note, listener));
        }
    }

    private void reloadCollections() {
        collectionRepository.loadCollections(collections -> {
            if (!isAdded()) return;
            allCollections = collections;
            applyFilters();
        });
    }

    private void reloadNotes() {
        noteRepository.loadNotes(null, notes -> {
            if (!isAdded()) return;
            allNotes = notes;
            applyFilters();
        });
    }

    /** Re-runs the filter and redraws the chip row — everything that changing a filter implies. */
    private void onFilterChanged() {
        applyFilters();
        searchBar.render(filter, allTags);
    }

    private void applyFilters() {
        homeAdapter.submitCollections(filter.applyToCollections(allCollections));
        // Boards are matched on their *displayed* title, so searching "untitled" finds the unnamed
        // ones — the fallback name is what the card shows.
        homeAdapter.submitWhiteboards(filter.applyToWhiteboards(allWhiteboards,
                board -> NoteDisplayUtils.resolveWhiteboardTitle(requireContext(), board)));
        homeAdapter.submitNotes(filter.apply(allNotes));
    }

    private void reloadTags() {
        tagRepository.loadAllTags(tags -> {
            if (!isAdded()) return;
            allTags = tags;
            // A tag deleted elsewhere would otherwise stay in the filter as a chip that can't be
            // resolved to a name, silently hiding every note.
            for (String selected : new ArrayList<>(filter.tagIds())) {
                boolean stillExists = false;
                for (Tag tag : allTags) {
                    if (tag.id.equals(selected)) { stillExists = true; break; }
                }
                if (!stillExists) filter.removeTag(selected);
            }
            onFilterChanged();
        });
    }
}
