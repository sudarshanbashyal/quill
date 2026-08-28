package mse.quill.ui.quiz;

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

import com.google.android.material.button.MaterialButton;

import java.util.List;

import com.google.android.material.snackbar.Snackbar;

import mse.quill.R;
import mse.quill.data.NoteStore;
import mse.quill.data.Repositories;
import mse.quill.data.QuizRepository;
import mse.quill.data.model.Quiz;
import mse.quill.data.model.QuizAttempt;
import mse.quill.util.NoteDisplayUtils;
import mse.quill.study.quiz.QuizRules;

/**
 * One quiz between sittings: what it will ask, how it has gone before, and the way in.
 *
 * <p>The question count is read from the <em>note</em>, not from the quiz row, every time this
 * screen opens. A quiz owns no questions — deleting half a note's Q&amp;A blocks makes its quiz
 * shorter, and deleting enough of them makes it unrunnable, which is a thing this screen has to be
 * able to say rather than something to discover after pressing Start.
 */
public class QuizDetailFragment extends Fragment {

    public static final String ARG_QUIZ_ID = "quiz_id";

    private QuizRepository quizRepository;
    private NoteStore noteRepository ;
    private QuizAttemptsAdapter adapter;

    private TextView titleView;
    private TextView subtitleView;
    private TextView emptyAttempts;
    private RecyclerView recyclerView;
    private MaterialButton startButton;
    private View deleteButton;

    private String quizId;
    private Quiz quiz;
    private int availableQuestions;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_quiz_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        quizRepository = new QuizRepository(requireContext());
        noteRepository = Repositories.notes(requireContext());

        titleView = view.findViewById(R.id.quiz_title);
        subtitleView = view.findViewById(R.id.quiz_subtitle);
        emptyAttempts = view.findViewById(R.id.empty_attempts);
        startButton = view.findViewById(R.id.start_button);
        deleteButton = view.findViewById(R.id.delete_quiz_button);

        adapter = new QuizAttemptsAdapter(this::showMarkedPaper);
        recyclerView = view.findViewById(R.id.recycler_attempts);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        view.findViewById(R.id.back_button).setOnClickListener(v -> leave());
        deleteButton.setOnClickListener(v -> confirmDelete());
        startButton.setOnClickListener(v -> startQuiz());

        quizId = getArguments() != null ? getArguments().getString(ARG_QUIZ_ID) : null;
        if (quizId == null) leave();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Everything here changes elsewhere: an attempt is sat on the next screen, and the note it
        // draws questions from is edited on another one again.
        reload();
    }

    private void reload() {
        if (quizId == null) return;
        quizRepository.loadQuiz(quizId, loaded -> {
            if (!isAdded()) return;
            if (loaded == null) {
                // Deleted from the list behind this screen — there is nothing left to show.
                leave();
                return;
            }
            quiz = loaded;
            titleView.setText(NoteDisplayUtils.resolveTitle(
                    requireContext(), quiz.noteTitle, quiz.noteCreatedAt));
            loadQuestionCount();
        });
        quizRepository.loadAttempts(quizId, attempts -> {
            if (!isAdded()) return;
            renderAttempts(attempts);
        });
    }

    private void loadQuestionCount() {
        noteRepository.loadNote(quiz.noteId, (note, segments) -> {
            if (!isAdded()) return;
            availableQuestions = QuizContent.pairsFrom(segments).size();
            renderReadiness();
        });
    }

    private void renderReadiness() {
        boolean ready = availableQuestions >= QuizRules.MIN_QA_BLOCKS;
        startButton.setEnabled(ready);
        subtitleView.setText(ready
                ? getString(R.string.quiz_detail_ready_format, availableQuestions,
                        formatBudget(QuizRules.totalTimeMs(availableQuestions)))
                : getString(R.string.quiz_detail_not_ready_format, availableQuestions,
                        QuizRules.MIN_QA_BLOCKS));
    }

    /** m:ss, matching the session's clock — the budget shown here is the figure that counts down. */
    private String formatBudget(long millis) {
        long seconds = millis / 1000;
        return getString(R.string.quiz_timer_format, seconds / 60, seconds % 60);
    }

    /**
     * Reopens a past sitting's paper — every question as it was asked, with what was chosen.
     *
     * <p>A dialog rather than a screen of its own: it is a thing you glance back at from the
     * history you are already looking at, and a destination would put it behind a navigation the
     * back stack then has to carry. It reuses the same row view the end of a live run shows, so a
     * paper looks identical whether it is two seconds or two weeks old.
     */
    private void showMarkedPaper(QuizAttempt attempt) {
        quizRepository.loadAttemptAnswers(attempt.id, results -> {
            if (!isAdded()) return;
            if (results.isEmpty()) {
                // Attempts sat before answers were stored. The score beside it is still true, so
                // this says what is missing rather than pretending the attempt was empty.
                Snackbar.make(requireView(), R.string.quiz_attempt_not_kept, Snackbar.LENGTH_LONG)
                        .show();
                return;
            }
            MarkedPaperDialog.show(requireContext(), attempt, results);
        });
    }

    private void renderAttempts(List<QuizAttempt> attempts) {
        adapter.submit(attempts);
        boolean empty = attempts.isEmpty();
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyAttempts.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void startQuiz() {
        Bundle args = new Bundle();
        args.putString(QuizSessionFragment.ARG_QUIZ_ID, quizId);
        NavHostFragment.findNavController(this).navigate(R.id.quizSessionFragment, args);
    }

    private void confirmDelete() {
        if (quiz == null) return;
        DeleteQuizDialog.show(requireContext(),
                NoteDisplayUtils.resolveTitle(
                        requireContext(), quiz.noteTitle, quiz.noteCreatedAt), () ->
                quizRepository.deleteQuiz(quiz.id, () -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), R.string.quiz_deleted, Toast.LENGTH_SHORT)
                            .show();
                    leave();
                }));
    }

    private void leave() {
        NavHostFragment.findNavController(this).navigateUp();
    }
}
