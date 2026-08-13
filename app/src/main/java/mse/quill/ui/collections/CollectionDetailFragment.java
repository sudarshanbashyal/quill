package mse.quill.ui.collections;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

import mse.quill.R;
import mse.quill.util.RelativeTime;
import mse.quill.data.AppExecutors;
import mse.quill.data.CollectionRepository;
import mse.quill.data.NoteRepository;
import mse.quill.data.model.Collection;
import mse.quill.data.TagRepository;
import mse.quill.data.model.Note;
import mse.quill.data.model.Tag;
import mse.quill.share.CollectionBundle;
import mse.quill.share.CollectionBundleWriter;
import mse.quill.ui.home.CollectionDialogs;
import mse.quill.ui.home.NotesAdapter;
import mse.quill.ui.notes.NoteEditorFragment;
import mse.quill.ui.search.NoteFilter;
import mse.quill.ui.search.SearchFilterBar;
import mse.quill.ui.search.SearchFilterDialog;
import mse.quill.util.NoteExportStore;

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
    private List<Tag> allTags = new ArrayList<>();
    /** Same control and same rules as Home's — see {@link NoteFilter}. */
    private final NoteFilter filter = new NoteFilter();
    private SearchFilterBar searchBar;
    private TagRepository tagRepository;

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
        tagRepository = new TagRepository(requireContext());
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

        searchBar = view.findViewById(R.id.search_bar);
        searchBar.setHint(R.string.search_hint_collection);
        searchBar.setListener(new SearchFilterBar.Listener() {
            @Override public void onQueryChanged(String query) {
                filter.setQuery(query);
                applyFilters();
            }

            @Override public void onFilterRequested() {
                SearchFilterDialog.show(requireContext(), filter, allTags,
                        CollectionDetailFragment.this::onFilterChanged);
            }

            @Override public void onFilterCleared() {
                onFilterChanged();
            }
        });

        view.findViewById(R.id.btn_add_note).setOnClickListener(v -> {
            Bundle noteArgs = new Bundle();
            noteArgs.putString(NoteEditorFragment.ARG_COLLECTION_ID, collectionId);
            NavHostFragment.findNavController(this).navigate(R.id.noteEditorFragment, noteArgs);
        });

        view.findViewById(R.id.btn_search_notes).setOnClickListener(v -> showAddExistingNotesDialog());

        view.findViewById(R.id.btn_share_collection).setOnClickListener(v -> shareCollection());
    }

    /**
     * Packs every note in the collection into a {@code .quillpack} and hands it to the system
     * share sheet — the collection-level sibling of a note's "Share to another Quill". Blocked the
     * same way a single locked note is: a bundle is plaintext, so sharing one out of a locked
     * collection would be the lock's only hole.
     */
    private void shareCollection() {
        collectionRepository.isLocked(collectionId, locked -> {
            if (!isAdded()) return;
            if (locked) {
                Toast.makeText(requireContext(), R.string.share_locked_collection, Toast.LENGTH_LONG).show();
                return;
            }
            int color = 0;
            for (Collection c : allCollections) {
                if (c.id.equals(collectionId)) color = c.color;
            }
            String name = ((TextView) requireView().findViewById(R.id.toolbar_title)).getText().toString();
            int finalColor = color;
            noteRepository.loadNotes(collectionId, notes -> {
                if (!isAdded()) return;
                if (notes.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.export_collection_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                List<String> noteIds = new ArrayList<>();
                for (Note note : notes) noteIds.add(note.id);
                packAndShare(name, finalColor, noteIds);
            });
        });
    }

    private void packAndShare(String name, int color, List<String> noteIds) {
        Context appContext = requireContext().getApplicationContext();
        AppExecutors.getInstance().diskIO(() -> {
            List<NoteRepository.NoteBundleData> bundleData = new ArrayList<>();
            for (String noteId : noteIds) {
                NoteRepository.NoteBundleData data = noteRepository.loadForBundleSync(noteId);
                if (data != null) bundleData.add(data);
            }

            NoteExportStore.Saved saved = NoteExportStore.save(appContext, name,
                    CollectionBundle.EXTENSION, CollectionBundle.MIME_TYPE,
                    out -> CollectionBundleWriter.write(name, color, bundleData, appContext, out));

            AppExecutors.getInstance().mainThread(() -> {
                if (!isAdded()) return;
                if (saved == null) {
                    Toast.makeText(requireContext(), R.string.share_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType(CollectionBundle.MIME_TYPE)
                        .putExtra(Intent.EXTRA_STREAM, saved.uri)
                        .putExtra(Intent.EXTRA_TITLE, saved.displayName)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(send, getString(R.string.share_collection_chooser)));
                } catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(requireContext(), R.string.share_no_target, Toast.LENGTH_LONG).show();
                }
            });
        });
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
                            RelativeTime.past(requireContext(), c.lastActivityAt)));
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
        tagRepository.loadAllTags(tags -> {
            if (!isAdded()) return;
            allTags = tags;
            onFilterChanged();
        });
    }

    private void onFilterChanged() {
        applyFilters();
        searchBar.render(filter, allTags);
    }

    private void applyFilters() {
        List<Note> filtered = filter.apply(allNotesInCollection);
        notesAdapter.submitList(filtered);
        emptyNotesView.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
