package mse.quill.ui.quiz;

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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mse.quill.R;
import mse.quill.data.NoteStore;
import mse.quill.data.Repositories;
import mse.quill.data.QuizRepository;
import mse.quill.data.model.Quiz;
import mse.quill.ui.notes.NoteQaPickerDialog;
import mse.quill.ui.notes.QaBlockHintDialog;
import mse.quill.util.NoteDisplayUtils;
import mse.quill.ui.common.SwipeToDelete;
import mse.quill.ui.common.UndoDelete;

/**
 * The Quizzes tab: every note that has been turned into a quiz.
 *
 * <p>Deliberately the same shape as the decks list, including the two ways to make one from here —
 * the header's "+" and the empty state's button — since they are the same kind of screen answering
 * the same complaint. The only difference is the bar a note has to clear: a deck wants one usable
 * Q&amp;A block, a quiz wants {@link QuizRules#MIN_QA_BLOCKS}.
 *
 * <p>Reloaded on resume, since everything that changes a row (sitting a quiz, deleting it, editing
 * the note) happens on another screen.
 */
public class QuizzesFragment extends Fragment {

    private QuizRepository quizRepository;
    private NoteStore noteRepository ;
    private QuizzesAdapter adapter;
    private RecyclerView recyclerView;
    private View emptyView;
    /** The notes already on this list, so the picker doesn't offer one whose quiz exists. */
    private final Set<String> notesWithQuizzes = new HashSet<>();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_quizzes, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        quizRepository = new QuizRepository(requireContext());
        noteRepository = Repositories.notes(requireContext());

        adapter = new QuizzesAdapter(new QuizzesAdapter.Listener() {
            @Override public void onQuizClicked(Quiz quiz) {
                openQuiz(quiz);
            }

            @Override public void onDeleteClicked(Quiz quiz) {
                confirmDelete(quiz);
            }
        });

        recyclerView = view.findViewById(R.id.recycler_quizzes);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
        SwipeToDelete.attach(recyclerView, new SwipeToDelete.Target() {
            @Override public boolean isSwipeable(RecyclerView.ViewHolder holder) { return true; }

            @Override public void onSwiped(RecyclerView.ViewHolder holder) {
                Quiz quiz = adapter.quizAt(holder.getBindingAdapterPosition());
                if (quiz != null) deleteWithUndo(quiz);
            }
        });

        emptyView = view.findViewById(R.id.empty_quizzes);
        // The minimum is a constant that's meant to be tuned, so the empty state asks for it rather
        // than repeating a hardcoded "5" that could quietly become a lie.
        ((TextView) view.findViewById(R.id.empty_quizzes_text))
                .setText(getString(R.string.quizzes_empty, QuizRules.MIN_QA_BLOCKS));

        View.OnClickListener create = v -> startCreateFlow();
        view.findViewById(R.id.create_quiz).setOnClickListener(create);
        view.findViewById(R.id.create_quiz_empty).setOnClickListener(create);
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        quizRepository.loadQuizzes(quizzes -> {
            if (!isAdded()) return;
            render(quizzes);
        });
    }

    private void render(List<Quiz> quizzes) {
        // See FlashcardDecksFragment.render: a quiz inside its undo window must not be re-listed
        // by the reload that put the bar on screen.
        List<Quiz> visible = new ArrayList<>();
        for (Quiz quiz : quizzes) {
            if (!UndoDelete.isHidden(undoKey(quiz))) visible.add(quiz);
        }
        quizzes = visible;

        adapter.submit(quizzes);
        notesWithQuizzes.clear();
        for (Quiz quiz : quizzes) notesWithQuizzes.add(quiz.noteId);
        boolean empty = quizzes.isEmpty();
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    /**
     * Asks which note to build a quiz from, then builds it and opens it.
     *
     * <p>Three ways to have nothing to offer, and they are told apart because they call for
     * different things: no note has a usable Q&amp;A block at all, none has enough of them, or every
     * note that qualifies already has a quiz. The first two are answered with the Q&amp;A hint
     * dialog, since both end in "write more blocks"; the third is answered with a Snackbar, since
     * it ends in nothing.
     */
    private void startCreateFlow() {
        noteRepository.loadQaCandidates(candidates -> {
            if (!isAdded()) return;
            List<NoteStore.QaCandidate> available = new ArrayList<>();
            boolean anyBigEnough = false;
            for (NoteStore.QaCandidate candidate : candidates) {
                if (candidate.usableQa < QuizRules.MIN_QA_BLOCKS) continue;
                anyBigEnough = true;
                if (!notesWithQuizzes.contains(candidate.note.id)) available.add(candidate);
            }
            if (available.isEmpty()) {
                showNothingToOffer(candidates.isEmpty(), anyBigEnough);
                return;
            }
            NoteQaPickerDialog.show(requireContext(), R.string.qa_picker_quiz_title,
                    available, this::createQuizFor);
        });
    }

    private void showNothingToOffer(boolean noQaAnywhere, boolean anyBigEnough) {
        // The first two are the same question the note editor answers with a picture of the
        // toolbar, so they get that picture rather than a sentence — the user needs to know where
        // Q&A blocks come from, and being on the Quizzes tab doesn't change that. What it does
        // change is the words around the picture: both cases are put in terms of the quiz, since
        // that is what was asked for.
        if (noQaAnywhere || !anyBigEnough) {
            QaBlockHintDialog.showForNoQuizAnywhere(
                    requireContext(), QuizRules.MIN_QA_BLOCKS, noQaAnywhere);
        } else {
            // Every note that qualifies already has a quiz: nothing to teach, nothing to do.
            Snackbar.make(requireView(), R.string.qa_picker_all_have_quizzes,
                    Snackbar.LENGTH_LONG).show();
        }
    }

    /** {@code ensureForNote} is the same call the note editor's "Make quiz" makes, so a quiz made
     *  here is indistinguishable from one made there — including reopening rather than duplicating
     *  if one somehow already exists. */
    private void createQuizFor(NoteStore.QaCandidate candidate) {
        quizRepository.ensureForNote(candidate.note.id, quiz -> {
            if (!isAdded()) return;
            openQuizById(quiz.id);
        });
    }

    private void openQuiz(Quiz quiz) {
        openQuizById(quiz.id);
    }

    private void openQuizById(String quizId) {
        Bundle args = new Bundle();
        args.putString(QuizDetailFragment.ARG_QUIZ_ID, quizId);
        NavHostFragment.findNavController(this).navigate(R.id.quizDetailFragment, args);
    }

    /** The row's delete button keeps its warning about losing every attempt and score; the swipe
     *  skips it and relies on the undo bar. */
    private void confirmDelete(Quiz quiz) {
        DeleteQuizDialog.show(requireContext(),
                NoteDisplayUtils.resolveTitle(
                        requireContext(), quiz.noteTitle, quiz.noteCreatedAt),
                () -> deleteWithUndo(quiz));
    }

    private void deleteWithUndo(Quiz quiz) {
        UndoDelete.offer(requireView(), getString(R.string.quiz_deleted), undoKey(quiz),
                this::reload,
                () -> quizRepository.deleteQuiz(quiz.id, null));
        reload();
    }

    private static String undoKey(Quiz quiz) {
        return "quiz:" + quiz.id;
    }
}
