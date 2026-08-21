package mse.quill.ui.collections;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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
import mse.quill.util.SwipeToDelete;
import mse.quill.util.UndoDelete;
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
    private View emptyNotesView;
    /** Shown only when the collection is genuinely empty — see {@link #applyFilters}. */
    private View emptyAddNoteButton;
    private TextView toolbarSubtitle;
    private EditText titleField;
    /** The name as the database has it, so an edit can be told from a redraw — and reverted to. */
    private String committedName = "";

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

        titleField = view.findViewById(R.id.toolbar_title);
        titleField.setText(collectionName);
        committedName = collectionName == null ? "" : collectionName;
        setUpRename();

        toolbarSubtitle = view.findViewById(R.id.toolbar_subtitle);
        view.findViewById(R.id.back_button).setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigateUp());

        emptyNotesView = view.findViewById(R.id.empty_notes);
        emptyAddNoteButton = view.findViewById(R.id.empty_add_note);

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
                        () -> deleteNoteWithUndo(note));
            }
        });
        recyclerView.setAdapter(notesAdapter);
        // Every row is a note, same as Home's notes section — the gesture shouldn't mean something
        // different depending on which screen the note is being read from.
        SwipeToDelete.attach(recyclerView, new SwipeToDelete.Target() {
            @Override public boolean isSwipeable(RecyclerView.ViewHolder holder) { return true; }

            @Override public void onSwiped(RecyclerView.ViewHolder holder) {
                Note note = notesAdapter.noteAt(holder.getBindingAdapterPosition());
                if (note != null) deleteNoteWithUndo(note);
            }
        });

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

        view.findViewById(R.id.btn_add_note).setOnClickListener(v -> showAddNoteChooser());
        view.findViewById(R.id.empty_add_note).setOnClickListener(v -> showAddNoteChooser());

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

    /**
     * Commits a rename when the field is finished with, rather than on every keystroke.
     *
     * <p>Two moments count as finished: Done on the keyboard, and the field losing focus — the
     * second is what catches the common case of typing a name and then tapping straight into a
     * note. Leaving the screen goes through {@code onPause}, which is the same commit again.
     *
     * <p>An empty name is refused rather than saved. A collection with no name is unreachable in
     * every list that shows one, and the field reverts so it is obvious nothing happened.
     */
    private void setUpRename() {
        titleField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            commitRename();
            titleField.clearFocus();
            InputMethodManager imm = requireContext().getSystemService(InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(titleField.getWindowToken(), 0);
            return true;
        });
        titleField.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) commitRename();
        });
    }

    private void commitRename() {
        if (titleField == null || collectionId == null) return;
        String typed = titleField.getText().toString().trim();

        if (typed.isEmpty()) {
            titleField.setText(committedName);
            return;
        }
        if (typed.equals(committedName)) return;

        committedName = typed;
        // Nothing on this screen is drawn from the name, so there is no reload to wait for; Home
        // rereads its collections when it comes back.
        collectionRepository.renameCollection(collectionId, typed, null);
    }

    @Override
    public void onPause() {
        super.onPause();
        // Backing out with the field still focused is a finished edit like any other.
        commitRename();
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

    /** Both ways in — the header's "+" and the empty state's button — ask the same question. */
    private void showAddNoteChooser() {
        CollectionDialogs.showAddNoteDialog(requireContext(), new CollectionDialogs.AddNoteListener() {
            @Override public void onNewNote() {
                Bundle noteArgs = new Bundle();
                noteArgs.putString(NoteEditorFragment.ARG_COLLECTION_ID, collectionId);
                NavHostFragment.findNavController(CollectionDetailFragment.this)
                        .navigate(R.id.noteEditorFragment, noteArgs);
            }

            @Override public void onExistingNote() {
                showAddExistingNotesDialog();
            }
        });
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

    private void deleteNoteWithUndo(Note note) {
        UndoDelete.offer(requireView(), getString(R.string.note_deleted), noteKey(note.id),
                this::reloadNotes,
                () -> noteRepository.deleteNote(note.id, null));
        reloadNotes();
    }

    /** The same key Home uses, so a note deleted on one screen is hidden on the other too. */
    private static String noteKey(String id) { return "note:" + id; }

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
        List<Note> filtered = new ArrayList<>();
        // A note waiting out its undo window is out of this list too, or reloading the collection
        // would put it back under the bar still offering to undo it.
        for (Note note : filter.apply(allNotesInCollection)) {
            if (!UndoDelete.isHidden(noteKey(note.id))) filtered.add(note);
        }
        notesAdapter.submitList(filtered);
        emptyNotesView.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        // The offer to add one belongs to an empty collection, not to a filter that happens to
        // match nothing: the notes are there, and "Add a note" is not the way to see them.
        emptyAddNoteButton.setVisibility(
                allNotesInCollection.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
