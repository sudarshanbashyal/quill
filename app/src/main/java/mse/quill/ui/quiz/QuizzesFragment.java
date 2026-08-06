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

import java.util.List;

import mse.quill.R;
import mse.quill.data.QuizRepository;
import mse.quill.data.model.Quiz;

/**
 * The Quizzes tab: every note that has been turned into a quiz.
 *
 * <p>Like the decks list, quizzes aren't created here — they appear when a note's Q&amp;A blocks are
 * made into one. Reloaded on resume, since everything that changes a row (sitting a quiz, deleting
 * it, editing the note) happens on another screen.
 */
public class QuizzesFragment extends Fragment {

    private QuizRepository quizRepository;
    private QuizzesAdapter adapter;
    private RecyclerView recyclerView;
    private TextView emptyView;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_quizzes, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        quizRepository = new QuizRepository(requireContext());

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

        emptyView = view.findViewById(R.id.empty_quizzes);
        // The minimum is a constant that's meant to be tuned, so the empty state asks for it rather
        // than repeating a hardcoded "5" that could quietly become a lie.
        emptyView.setText(getString(R.string.quizzes_empty, QuizRules.MIN_QA_BLOCKS));
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
        adapter.submit(quizzes);
        boolean empty = quizzes.isEmpty();
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void openQuiz(Quiz quiz) {
        Bundle args = new Bundle();
        args.putString(QuizDetailFragment.ARG_QUIZ_ID, quiz.id);
        NavHostFragment.findNavController(this).navigate(R.id.quizDetailFragment, args);
    }

    private void confirmDelete(Quiz quiz) {
        DeleteQuizDialog.show(requireContext(), quiz.noteTitle, () ->
                quizRepository.deleteQuiz(quiz.id, () -> {
                    if (!isAdded()) return;
                    Snackbar.make(requireView(), R.string.quiz_deleted, Snackbar.LENGTH_SHORT)
                            .show();
                    reload();
                }));
    }
}
