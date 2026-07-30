package mse.quill.ui.flashcards;

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

import com.google.android.material.snackbar.Snackbar;

import java.util.List;

import mse.quill.R;
import mse.quill.data.FlashcardRepository;
import mse.quill.data.model.FlashcardDeck;

/**
 * The Flashcards tab: every note that has generated cards, most urgent first.
 *
 * <p>Decks aren't a thing the user creates here — they appear the moment a note's Q&amp;A blocks are
 * turned into cards, and each row leads back into review for that note. Reloaded on resume, since
 * both of the things that change it (reviewing a deck, editing a note) happen elsewhere.
 */
public class FlashcardDecksFragment extends Fragment {

    private FlashcardRepository flashcardRepository;
    private FlashcardDecksAdapter adapter;
    private RecyclerView recyclerView;
    private TextView emptyView;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_flashcard_decks, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        flashcardRepository = new FlashcardRepository(requireContext());

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
        emptyView = view.findViewById(R.id.empty_decks);
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

    private void render(List<FlashcardDeck> decks) {
        adapter.submit(decks);
        boolean empty = decks.isEmpty();
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void openDeck(FlashcardDeck deck) {
        Bundle args = new Bundle();
        args.putString(FlashcardsFragment.ARG_NOTE_ID, deck.noteId);
        NavHostFragment.findNavController(this).navigate(R.id.flashcardsFragment, args);
    }

    private void confirmDelete(FlashcardDeck deck) {
        DeleteFlashcardsDialog.show(requireContext(), deck.noteTitle, () ->
                flashcardRepository.deleteForNote(deck.noteId, () -> {
                    if (!isAdded()) return;
                    Snackbar.make(requireView(), R.string.flashcards_deleted, Snackbar.LENGTH_SHORT)
                            .show();
                    reload();
                }));
    }
}
