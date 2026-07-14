package mse.quill.ui.collections;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import mse.quill.R;
import mse.quill.data.CollectionRepository;
import mse.quill.data.NoteRepository;
import mse.quill.data.model.Collection;
import mse.quill.data.model.Note;
import mse.quill.ui.home.CollectionDialogs;
import mse.quill.ui.home.NotesAdapter;
import mse.quill.ui.notes.NoteEditorFragment;

public class CollectionDetailFragment extends Fragment {

    public static final String ARG_COLLECTION_ID = "collection_id";
    public static final String ARG_COLLECTION_NAME = "collection_name";

    private NoteRepository noteRepository;
    private CollectionRepository collectionRepository;
    private NotesAdapter notesAdapter;
    private TextView emptyNotesView;
    private TextView toolbarSubtitle;

    private String collectionId;
    private List<Collection> allCollections = new ArrayList<>();
    private List<Note> allNotesInCollection = new ArrayList<>();
    private String searchQuery = "";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                              Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_collection_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        collectionId = args.getString(ARG_COLLECTION_ID);
        String collectionName = args.getString(ARG_COLLECTION_NAME);

        noteRepository = new NoteRepository(requireContext());
        collectionRepository = new CollectionRepository(requireContext());

        ((TextView) view.findViewById(R.id.toolbar_title)).setText(collectionName);
        toolbarSubtitle = view.findViewById(R.id.toolbar_subtitle);
        view.findViewById(R.id.back_button).setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigateUp());

        emptyNotesView = view.findViewById(R.id.empty_notes);

        RecyclerView recyclerView = view.findViewById(R.id.recycler_notes);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        notesAdapter = new NotesAdapter(new NotesAdapter.Listener() {
            @Override public void onNoteClicked(Note note) {
                Bundle noteArgs = new Bundle();
                noteArgs.putString(NoteEditorFragment.ARG_NOTE_ID, note.id);
                NavHostFragment.findNavController(CollectionDetailFragment.this)
                        .navigate(R.id.noteEditorFragment, noteArgs);
            }

            @Override public void onNoteLongPressed(Note note) {
                boolean isPinned = note.pinnedAt != null;
                CollectionDialogs.showNoteActionsDialog(requireContext(), allCollections, collectionId, isPinned,
                        newCollectionId -> noteRepository.assignCollection(note.id, newCollectionId,
                                CollectionDetailFragment.this::reloadNotes),
                        () -> togglePin(note, isPinned),
                        () -> noteRepository.deleteNote(note.id, CollectionDetailFragment.this::reloadNotes));
            }
        });
        recyclerView.setAdapter(notesAdapter);

        EditText searchInput = view.findViewById(R.id.search_input);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                searchQuery = s.toString().trim().toLowerCase(Locale.getDefault());
                applyFilters();
            }
        });

        view.findViewById(R.id.btn_add_note).setOnClickListener(v -> {
            Bundle noteArgs = new Bundle();
            noteArgs.putString(NoteEditorFragment.ARG_COLLECTION_ID, collectionId);
            NavHostFragment.findNavController(this).navigate(R.id.noteEditorFragment, noteArgs);
        });

        view.findViewById(R.id.btn_search_notes).setOnClickListener(v -> showAddExistingNotesDialog());
    }

    @Override
    public void onResume() {
        super.onResume();
        collectionRepository.loadCollections(collections -> {
            if (!isAdded()) return;
            allCollections = collections;
            for (Collection c : collections) {
                if (c.id.equals(collectionId)) {
                    toolbarSubtitle.setText(getString(R.string.updated_relative_format,
                            DateUtils.getRelativeTimeSpanString(
                                    c.lastActivityAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)));
                    break;
                }
            }
        });
        reloadNotes();
    }

    private void togglePin(Note note, boolean isPinned) {
        if (isPinned) {
            noteRepository.unpinNote(note.id, this::reloadNotes);
        } else {
            noteRepository.pinNote(note.id, new NoteRepository.OnPinResult() {
                @Override public void onPinned() { reloadNotes(); }

                @Override public void onLimitReached() {
                    if (isAdded()) {
                        Toast.makeText(requireContext(), R.string.pin_limit_reached_message, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    private void showAddExistingNotesDialog() {
        noteRepository.loadNotes(null, notes -> {
            if (!isAdded()) return;
            List<Note> candidates = new ArrayList<>();
            for (Note note : notes) {
                if (!collectionId.equals(note.collectionId)) candidates.add(note);
            }
            AddExistingNotesDialog.show(requireContext(), noteRepository, collectionId, candidates, this::reloadNotes);
        });
    }

    private void reloadNotes() {
        noteRepository.loadNotes(collectionId, notes -> {
            if (!isAdded()) return;
            allNotesInCollection = notes;
            applyFilters();
        });
    }

    private void applyFilters() {
        List<Note> filtered;
        if (searchQuery.isEmpty()) {
            filtered = allNotesInCollection;
        } else {
            filtered = new ArrayList<>();
            for (Note n : allNotesInCollection) {
                String title = n.title == null ? "" : n.title.toLowerCase(Locale.getDefault());
                String preview = n.preview == null ? "" : n.preview.toLowerCase(Locale.getDefault());
                if (title.contains(searchQuery) || preview.contains(searchQuery)) filtered.add(n);
            }
        }
        notesAdapter.submitList(filtered);
        emptyNotesView.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
