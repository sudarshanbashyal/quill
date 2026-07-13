package mse.quill.ui.home;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import mse.quill.R;
import mse.quill.data.CollectionRepository;
import mse.quill.data.NoteRepository;
import mse.quill.data.model.Collection;
import mse.quill.data.model.Note;
import mse.quill.ui.collections.CollectionDetailFragment;
import mse.quill.ui.notes.NoteEditorFragment;
import mse.quill.util.ColorUtils;

public class HomeFragment extends Fragment {

    private NoteRepository noteRepository;
    private CollectionRepository collectionRepository;

    private HomeAdapter homeAdapter;
    private View pinnedSection;
    private LinearLayout pinnedCardsContainer;

    private List<Collection> allCollections = new ArrayList<>();
    private List<Note> allNotes = new ArrayList<>();
    private String searchQuery = "";

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
        });

        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), 2);
        layoutManager.setSpanSizeLookup(homeAdapter.spanSizeLookup());

        RecyclerView recyclerView = view.findViewById(R.id.recycler_home);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(homeAdapter);

        pinnedSection = view.findViewById(R.id.pinned_section);
        pinnedCardsContainer = view.findViewById(R.id.pinned_cards_container);

        EditText searchInput = view.findViewById(R.id.search_input);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                searchQuery = s.toString().trim().toLowerCase(Locale.getDefault());
                applyFilters();
            }
        });

        setupFabMenu(view);
    }

    private void setupFabMenu(View view) {
        View fabOptions = view.findViewById(R.id.fab_options);
        View fabOptionNote = view.findViewById(R.id.fab_option_note);
        View fabOptionCollection = view.findViewById(R.id.fab_option_collection);

        view.findViewById(R.id.fab_new_note).setOnClickListener(v -> {
            boolean expanded = fabOptions.getVisibility() == View.VISIBLE;
            fabOptions.setVisibility(expanded ? View.GONE : View.VISIBLE);
        });

        fabOptionNote.setOnClickListener(v -> {
            fabOptions.setVisibility(View.GONE);
            NavHostFragment.findNavController(this).navigate(R.id.noteEditorFragment);
        });

        fabOptionCollection.setOnClickListener(v -> {
            fabOptions.setVisibility(View.GONE);
            CollectionDialogs.showCreateDialog(requireContext(), name ->
                    collectionRepository.createCollection(
                            name, ColorUtils.randomPaletteColor(requireContext()), id -> reloadCollections()));
        });
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

    private void showManageCollectionDialog(Collection collection) {
        CollectionDialogs.showManageDialog(requireContext(), collection, new CollectionDialogs.ManageListener() {
            @Override public void onRename() {
                CollectionDialogs.showRenameDialog(requireContext(), collection.name, newName ->
                        collectionRepository.renameCollection(collection.id, newName, HomeFragment.this::reloadCollections));
            }

            @Override public void onDelete() {
                new AlertDialog.Builder(requireContext())
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
        reloadNotes();
        reloadPinnedNotes();
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

    private void applyFilters() {
        if (searchQuery.isEmpty()) {
            homeAdapter.submitCollections(allCollections);
            homeAdapter.submitNotes(allNotes);
            return;
        }

        List<Collection> filteredCollections = new ArrayList<>();
        for (Collection c : allCollections) {
            if (c.name.toLowerCase(Locale.getDefault()).contains(searchQuery)) filteredCollections.add(c);
        }
        homeAdapter.submitCollections(filteredCollections);

        List<Note> filteredNotes = new ArrayList<>();
        for (Note n : allNotes) {
            String title = n.title == null ? "" : n.title.toLowerCase(Locale.getDefault());
            String preview = n.preview == null ? "" : n.preview.toLowerCase(Locale.getDefault());
            if (title.contains(searchQuery) || preview.contains(searchQuery)) filteredNotes.add(n);
        }
        homeAdapter.submitNotes(filteredNotes);
    }
}
