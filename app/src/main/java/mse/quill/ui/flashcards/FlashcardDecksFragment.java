package mse.quill.ui.flashcards;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mse.quill.R;
import mse.quill.data.FlashcardStore;
import mse.quill.data.Repositories;
import mse.quill.data.NoteStore;
import mse.quill.data.Repositories;
import mse.quill.data.model.FlashcardDeck;
import mse.quill.ui.notes.QaBlockHintDialog;
import mse.quill.ui.notes.NoteQaPickerDialog;
import mse.quill.util.NoteDisplayUtils;
import mse.quill.ui.common.SwipeToDelete;
import mse.quill.ui.common.UndoDelete;

/**
 * The Flashcards tab: every note that has generated cards, most urgent first.
 *
 * <p>Decks can now be made from here as well as from inside a note — a "+" in the header and, when
 * there is nothing yet, a button in the empty state. Both open the same note picker. The tab used
 * to be strictly a read-only view of what the editor had produced, which meant the screen whose
 * entire subject is flashcards was the one place you couldn't make any: the empty state described
 * a menu item on another screen and left the user to go and find it.
 *
 * <p>Reloaded on resume, since everything that changes a row (reviewing a deck, editing a note)
 * happens elsewhere.
 */
public class FlashcardDecksFragment extends Fragment {

    private FlashcardStore flashcardRepository ;
    private NoteStore noteRepository ;
    private FlashcardDecksAdapter adapter;
    private RecyclerView recyclerView;
    private View emptyView;
    private com.google.android.material.button.MaterialButton reviewAllButton;
    /** The notes already on this list, so the picker doesn't offer one whose deck exists. */
    private final Set<String> notesWithDecks = new HashSet<>();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_flashcard_decks, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        flashcardRepository = Repositories.flashcards(requireContext());
        noteRepository = Repositories.notes(requireContext());

        adapter = new FlashcardDecksAdapter(new FlashcardDecksAdapter.Listener() {
            @Override public void onDeckClicked(FlashcardDeck deck) {
                openDeck(deck);
            }

            @Override public void onDeleteClicked(FlashcardDeck deck) {
                confirmDelete(deck);
            }
        });

        recyclerView = view.findViewById(R.id.recycler_decks);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
        // Every row here is a deck, so every row swipes. No confirmation on this path: the undo
        // bar is the confirmation, and a dialog in front of a gesture whose whole point is speed
        // would make the gesture pointless.
        SwipeToDelete.attach(recyclerView, new SwipeToDelete.Target() {
            @Override public boolean isSwipeable(RecyclerView.ViewHolder holder) { return true; }

            @Override public void onSwiped(RecyclerView.ViewHolder holder) {
                FlashcardDeck deck = adapter.deckAt(holder.getBindingAdapterPosition());
                if (deck != null) deleteWithUndo(deck);
            }
        });
        emptyView = view.findViewById(R.id.empty_decks);

        reviewAllButton = view.findViewById(R.id.review_all_due);
        reviewAllButton.setOnClickListener(v -> openGlobalSession());

        View.OnClickListener create = v -> startCreateFlow();
        view.findViewById(R.id.create_deck).setOnClickListener(create);
        view.findViewById(R.id.create_deck_empty).setOnClickListener(create);
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        flashcardRepository.loadDecks(decks -> {
            if (!isAdded()) return;
            render(decks);
        });
    }

    /**
     * Sets the "review everything due" button from the rows actually on screen.
     *
     * <p>Summed from the visible decks rather than counted in SQL, which is what it used to do and
     * what made it wrong: a deck waiting out its undo window is gone from the list but still in the
     * database, so the query kept counting its cards and the button went on offering ten while nine
     * were listed. Summing what is rendered cannot disagree with what is rendered.
     */
    private void renderDueTotal(List<FlashcardDeck> visibleDecks) {
        int due = 0;
        for (FlashcardDeck deck : visibleDecks) due += deck.due;
        reviewAllButton.setVisibility(due > 0 ? View.VISIBLE : View.GONE);
        if (due > 0) {
            reviewAllButton.setText(getResources().getQuantityString(
                    R.plurals.flashcards_review_all, due, due));
        }
    }

    /** The same review screen, with no note id — see {@link FlashcardsFragment}. */
    private void openGlobalSession() {
        NavHostFragment.findNavController(this).navigate(R.id.flashcardsFragment);
    }

    private void render(List<FlashcardDeck> decks) {
        // A deck waiting out its undo window is gone as far as the user is concerned, and this
        // list is rebuilt on every resume — without the filter it would come back up underneath
        // the bar still offering to undo it.
        List<FlashcardDeck> visible = new ArrayList<>();
        for (FlashcardDeck deck : decks) {
            if (!UndoDelete.isHidden(undoKey(deck))) visible.add(deck);
        }
        decks = visible;

        adapter.submit(decks);
        renderDueTotal(decks);
        notesWithDecks.clear();
        for (FlashcardDeck deck : decks) notesWithDecks.add(deck.noteId);
        boolean empty = decks.isEmpty();
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    /**
     * Asks which note to make a deck from, then makes it and opens it.
     *
     * <p>Notes that already have a deck are filtered out rather than shown and refused: picking one
     * would be a no-op that lands you on a screen you could have reached by tapping its row. When
     * that leaves nothing, the two reasons are told apart — no note has a usable Q&amp;A block at
     * all, or they all have decks already — because the first is something the user can act on and
     * the second is nothing to do. Only the first gets the hint dialog: it is the one with
     * something to teach.
     */
    private void startCreateFlow() {
        noteRepository.loadQaCandidates(candidates -> {
            if (!isAdded()) return;
            List<NoteStore.QaCandidate> available = new ArrayList<>();
            for (NoteStore.QaCandidate candidate : candidates) {
                if (!notesWithDecks.contains(candidate.note.id)) available.add(candidate);
            }
            if (available.isEmpty()) {
                if (candidates.isEmpty()) {
                    // Nothing to pick because nothing has been written yet — which is a question
                    // about Q&A blocks, so it gets the same illustrated answer the note editor
                    // gives, minus the offer to add one (there is no note open to add it to).
                    QaBlockHintDialog.showForNoQaAnywhere(requireContext());
                } else {
                    // Every candidate already has a deck. Nothing to teach and nothing to do, so
                    // this one stays a Snackbar.
                    Snackbar.make(requireView(), R.string.qa_picker_all_have_flashcards,
                            Snackbar.LENGTH_LONG).show();
                }
                return;
            }
            NoteQaPickerDialog.show(requireContext(), R.string.qa_picker_flashcards_title,
                    available, this::createDeckFor);
        });
    }

    /** The generation itself is {@code syncFromNote}, the same call the deck screen makes on open —
     *  so a deck made here and one made from the note are the same deck, built the same way. */
    private void createDeckFor(NoteStore.QaCandidate candidate) {
        noteRepository.loadNote(candidate.note.id, (note, segments) -> {
            if (!isAdded()) return;
            flashcardRepository.syncFromNote(candidate.note.id, segments, cards -> {
                if (!isAdded()) return;
                openDeckFor(candidate.note.id);
            });
        });
    }

    private void openDeck(FlashcardDeck deck) {
        openDeckFor(deck.noteId);
    }

    private void openDeckFor(String noteId) {
        Bundle args = new Bundle();
        args.putString(FlashcardsFragment.ARG_NOTE_ID, noteId);
        NavHostFragment.findNavController(this).navigate(R.id.flashcardsFragment, args);
    }

    /** The row's own delete button keeps its warning about losing review progress; only the
     *  swipe skips it. Both end in the same undo bar. */
    private void confirmDelete(FlashcardDeck deck) {
        DeleteFlashcardsDialog.show(requireContext(),
                NoteDisplayUtils.resolveTitle(
                        requireContext(), deck.noteTitle, deck.noteCreatedAt),
                () -> deleteWithUndo(deck));
    }

    private void deleteWithUndo(FlashcardDeck deck) {
        UndoDelete.offer(requireView(), getString(R.string.flashcards_deleted), undoKey(deck),
                this::reload,
                // Reloaded on the way out too. The row left the list when the bar appeared, but
                // nothing had actually been written yet — without this the screen keeps whatever it
                // last read from the database until something else happens to reload it.
                () -> flashcardRepository.deleteForNote(deck.noteId, () -> {
                    if (isAdded()) reload();
                }));
        reload();
    }

    /** A deck is identified by its note, which is also what the delete is keyed on. */
    private static String undoKey(FlashcardDeck deck) {
        return deckUndoKey(deck.noteId);
    }

    /** Shared with {@link FlashcardsFragment}, so the global session can leave out a deck that is
     *  mid-undo here — the two would otherwise disagree about what is due. */
    static String deckUndoKey(String noteId) {
        return "deck:" + noteId;
    }
}
