package mse.quill.ui.collections;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

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

    /** Null means this screen represents the Uncategorized bucket. */
    private String collectionId;
    private List<Collection> allCollections = new ArrayList<>();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                              Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_collection_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        collectionId = args != null ? args.getString(ARG_COLLECTION_ID) : null;
        String collectionName = args != null && args.getString(ARG_COLLECTION_NAME) != null
                ? args.getString(ARG_COLLECTION_NAME)
                : getString(R.string.uncategorized);

        noteRepository = new NoteRepository(requireContext());
        collectionRepository = new CollectionRepository(requireContext());

        ((TextView) view.findViewById(R.id.toolbar_title)).setText(collectionName);
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
                CollectionDialogs.showNoteActionsDialog(requireContext(), allCollections, collectionId,
                        newCollectionId -> noteRepository.assignCollection(note.id, newCollectionId,
                                CollectionDetailFragment.this::reloadNotes),
                        () -> noteRepository.deleteNote(note.id, CollectionDetailFragment.this::reloadNotes));
            }
        });
        recyclerView.setAdapter(notesAdapter);

        view.findViewById(R.id.fab_new_note).setOnClickListener(v -> {
            Bundle noteArgs = new Bundle();
            if (collectionId != null) noteArgs.putString(NoteEditorFragment.ARG_COLLECTION_ID, collectionId);
            NavHostFragment.findNavController(this).navigate(R.id.noteEditorFragment, noteArgs);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        collectionRepository.loadCollections(collections -> {
            if (isAdded()) allCollections = collections;
        });
        reloadNotes();
    }

    private void reloadNotes() {
        String filter = collectionId == null ? NoteRepository.UNCATEGORIZED : collectionId;
        noteRepository.loadNotes(filter, notes -> {
            if (!isAdded()) return;
            notesAdapter.submitList(notes);
            emptyNotesView.setVisibility(notes.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }
}
