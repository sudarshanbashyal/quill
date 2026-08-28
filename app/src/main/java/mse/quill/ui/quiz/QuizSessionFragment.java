package mse.quill.ui.quiz;

import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import mse.quill.R;
import mse.quill.data.NoteStore;
import mse.quill.data.Repositories;
import mse.quill.data.QuizRepository;
import mse.quill.data.model.Quiz;
import mse.quill.data.serialization.MarkdownSerializer;
import mse.quill.study.quiz.QuizGenerator;
import mse.quill.study.quiz.QuizQuestion;
import mse.quill.study.quiz.QuizRules;
import mse.quill.study.quiz.QuizSession;

/**
 * Sitting one quiz: a timed paper of multiple-choice questions, marked when it's handed in.
 *
 * <p>The questions are generated here, from the note read back at the moment Start was pressed —
 * nothing about a quiz is stored except that it exists, so a quiz can't drift out of step with the
 * note it came from and every attempt draws a fresh set of wrong options.
 *
 * <p>One clock for the whole run ({@link QuizRules#totalTimeMs}), not one per question. That is
 * what makes the rest of this screen possible: questions can be answered in any order, revisited,
 * changed, or left blank and returned to, because leaving a question doesn't seal it. The indicator
 * row is how the user finds what they left, and the paper can be handed in with blanks on it — it
 * just asks first.
 */
public class QuizSessionFragment extends Fragment {

    public static final String ARG_QUIZ_ID = "quiz_id";

    /** Resolution of the timer bar. Higher than a per-second tick so the bar slides rather than
     *  stepping, which is what makes running out of time feel like it's approaching. */
    private static final int TIMER_STEPS = 1000;
    private static final long TIMER_TICK_MS = 50L;

    private TextView positionText;
    private TextView timerText;
    private LinearProgressIndicator timerProgress;
    private HorizontalScrollView indicatorScroll;
    private LinearLayout indicatorRow;
    private TextView timeWarning;
    private View questionScroll;
    private TextView questionText;
    private LinearLayout optionsContainer;
    private View navRow;
    private MaterialButton previousButton;
    private MaterialButton nextButton;
    private MaterialButton submitButton;

    private View resultsPanel;
    private TextView resultsTitle;
    private TextView resultsSummary;
    private RecyclerView resultsRecycler;
    private MaterialButton retakeButton;
    private MaterialButton doneButton;
    private QuizResultsAdapter resultsAdapter;

    private QuizRepository quizRepository;
    private NoteStore noteRepository ;

    private String quizId;
    private Quiz quiz;
    private String attemptId;
    private boolean attemptClosed;
    /** Set when the app went to the background mid-quiz; acted on once the screen comes back. */
    private boolean abandonedWhileAway;

    private QuizSession session;
    private final List<QuizOptionView.Views> optionViews = new ArrayList<>();
    private final List<TextView> indicators = new ArrayList<>();
    private CountDownTimer timer;
    private long remainingMs;
    private boolean warningShown;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_quiz_session, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        quizRepository = new QuizRepository(requireContext());
        noteRepository = Repositories.notes(requireContext());

        positionText = view.findViewById(R.id.question_position);
        timerText = view.findViewById(R.id.timer_text);
        timerProgress = view.findViewById(R.id.timer_progress);
        indicatorScroll = view.findViewById(R.id.indicator_scroll);
        indicatorRow = view.findViewById(R.id.indicator_row);
        timeWarning = view.findViewById(R.id.time_warning);
        questionScroll = view.findViewById(R.id.question_scroll);
        questionText = view.findViewById(R.id.question_text);
        optionsContainer = view.findViewById(R.id.options_container);
        navRow = view.findViewById(R.id.nav_row);
        previousButton = view.findViewById(R.id.previous_button);
        nextButton = view.findViewById(R.id.next_button);
        submitButton = view.findViewById(R.id.submit_button);

        resultsPanel = view.findViewById(R.id.results_panel);
        resultsTitle = view.findViewById(R.id.results_title);
        resultsSummary = view.findViewById(R.id.results_summary);
        resultsRecycler = view.findViewById(R.id.recycler_results);
        retakeButton = view.findViewById(R.id.retake_button);
        doneButton = view.findViewById(R.id.done_button);

        resultsAdapter = new QuizResultsAdapter();
        resultsRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        resultsRecycler.setAdapter(resultsAdapter);

        timerProgress.setMax(TIMER_STEPS);
        previousButton.setOnClickListener(v -> move(-1));
        nextButton.setOnClickListener(v -> move(1));
        submitButton.setOnClickListener(v -> confirmSubmit());
        retakeButton.setOnClickListener(v -> load());
        doneButton.setOnClickListener(v -> leave());
        view.findViewById(R.id.back_button).setOnClickListener(v -> confirmLeave());

        // The system back gesture is the likeliest way out of a quiz, so it gets the same
        // confirmation as the arrow rather than silently binning the attempt.
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() {
                        confirmLeave();
                    }
                });

        quizId = getArguments() != null ? getArguments().getString(ARG_QUIZ_ID) : null;
        if (quizId == null) {
            leave();
            return;
        }
        load();
    }

    // ── Setting up a run ───────────────────────────────────────────────────

    private void load() {
        quizRepository.loadQuiz(quizId, loaded -> {
            if (!isAdded()) return;
            if (loaded == null) {
                leave();
                return;
            }
            quiz = loaded;
            noteRepository.loadNote(quiz.noteId, (note, segments) -> {
                if (!isAdded()) return;
                begin(QuizGenerator.generate(QuizContent.pairsFrom(segments), new Random()));
            });
        });
    }

    private void begin(List<QuizQuestion> questions) {
        if (questions.isEmpty()) {
            // Only reachable when the note lost Q&A blocks between the detail screen loading it and
            // Start being pressed — the button is disabled for the case we know about in advance.
            showUnavailable();
            return;
        }

        session = new QuizSession(questions);
        attemptClosed = false;
        resetWarningState();
        resultsPanel.setVisibility(View.GONE);
        questionScroll.setVisibility(View.VISIBLE);
        navRow.setVisibility(View.VISIBLE);
        submitButton.setVisibility(View.VISIBLE);
        timerText.setVisibility(View.VISIBLE);
        timerProgress.setVisibility(View.VISIBLE);
        indicatorScroll.setVisibility(View.VISIBLE);

        quizRepository.startAttempt(quizId, questions.size(), id -> {
            if (!isAdded()) return;
            attemptId = id;
        });

        buildIndicators();
        showQuestion();
        startTimer(QuizRules.totalTimeMs(questions.size()));
    }

    /** One pip per question, built once per run — they outlive the question on screen, unlike the
     *  option views, because their whole job is to describe the questions that aren't. */
    private void buildIndicators() {
        indicatorRow.removeAllViews();
        indicators.clear();
        for (int i = 0; i < session.total(); i++) {
            TextView pip = QuizIndicatorView.build(requireContext());
            pip.setText(String.valueOf(i + 1));
            int index = i;
            pip.setOnClickListener(v -> {
                session.goTo(index);
                showQuestion();
            });
            indicatorRow.addView(pip);
            indicators.add(pip);
        }
    }

    // ── One question ───────────────────────────────────────────────────────

    private void move(int delta) {
        if (session == null) return;
        if (delta > 0) session.next();
        else session.previous();
        showQuestion();
    }

    private void showQuestion() {
        QuizQuestion question = session.current();
        if (question == null) return;

        positionText.setText(getString(R.string.quiz_position_format,
                session.position(), session.total()));
        questionText.setText(MarkdownSerializer.fromMarkdown(question.prompt));

        optionsContainer.removeAllViews();
        optionViews.clear();
        int selected = session.currentSelection();
        for (int i = 0; i < question.options.size(); i++) {
            QuizOptionView.Views option = QuizOptionView.build(requireContext());
            option.label.setText(MarkdownSerializer.fromMarkdown(question.options.get(i)));
            QuizOptionView.setSelected(option.card, i == selected);
            int index = i;
            option.card.setOnClickListener(v -> select(index));
            optionsContainer.addView(option.card);
            optionViews.add(option);
        }

        previousButton.setEnabled(session.hasPrevious());
        nextButton.setEnabled(session.hasNext());
        refreshIndicators();
    }

    /** Records the answer in place. Tapping the chosen option again clears it — on a paper that can
     *  be revisited, a mis-tap shouldn't be permanent. */
    private void select(int index) {
        session.select(index);
        int selected = session.currentSelection();
        for (int i = 0; i < optionViews.size(); i++) {
            QuizOptionView.setSelected(optionViews.get(i).card, i == selected);
        }
        refreshIndicators();
    }

    private void refreshIndicators() {
        for (int i = 0; i < indicators.size(); i++) {
            TextView pip = indicators.get(i);
            boolean answered = session.isAnswered(i);
            boolean current = i == session.currentIndex();
            QuizIndicatorView.setState(pip, answered, current);
            pip.setContentDescription(getString(R.string.quiz_indicator_description_format, i + 1,
                    getString(answered ? R.string.quiz_indicator_answered
                                       : R.string.quiz_indicator_unanswered)));
            if (current) keepVisible(pip);
        }
    }

    /** Scrolls the row so the current pip is on screen — with more questions than fit, jumping to
     *  the next one would otherwise move a highlight nobody can see. */
    private void keepVisible(View pip) {
        indicatorScroll.post(() -> indicatorScroll.requestChildRectangleOnScreen(pip,
                new android.graphics.Rect(0, 0, pip.getWidth(), pip.getHeight()), false));
    }

    // ── The clock ──────────────────────────────────────────────────────────

    private void startTimer(long durationMs) {
        cancelTimer();
        remainingMs = durationMs;
        long budget = QuizRules.totalTimeMs(session.total());

        timer = new CountDownTimer(durationMs, TIMER_TICK_MS) {
            @Override
            public void onTick(long millisLeft) {
                remainingMs = millisLeft;
                renderTime(millisLeft, budget);
            }

            @Override
            public void onFinish() {
                remainingMs = 0;
                renderTime(0, budget);
                // Out of time is a finished sitting, not an abandoned one: every question was put,
                // and the blanks are answers the user didn't get to. Marked as it stands.
                showResults(true);
            }
        };
        renderTime(durationMs, budget);
        timer.start();
    }

    private void renderTime(long millisLeft, long budget) {
        long totalSeconds = (long) Math.ceil(millisLeft / 1000.0);
        timerText.setText(getString(R.string.quiz_timer_format,
                totalSeconds / 60, totalSeconds % 60));
        timerProgress.setProgressCompat((int) (millisLeft * TIMER_STEPS / Math.max(1, budget)),
                false);

        boolean warning = millisLeft <= QuizRules.WARNING_TIME_MS;
        if (warning && !warningShown) enterWarningState();
    }

    /**
     * The last stretch: the clock turns red and says what running out will cost.
     *
     * <p>Latched rather than re-evaluated per tick — the styling only ever needs applying once, and
     * a warning that reappeared on every frame would fight the user's attention rather than get it.
     */
    private void enterWarningState() {
        warningShown = true;
        int alarm = ContextCompat.getColor(requireContext(), R.color.answer_incorrect);
        timerText.setTextColor(alarm);
        timerProgress.setIndicatorColor(alarm);
        timeWarning.setText(getString(R.string.quiz_time_warning_format,
                (int) (QuizRules.WARNING_TIME_MS / 1000)));
        timeWarning.setVisibility(View.VISIBLE);
    }

    private void resetWarningState() {
        warningShown = false;
        timeWarning.setVisibility(View.GONE);
        timerText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
        timerProgress.setIndicatorColor(
                ContextCompat.getColor(requireContext(), R.color.brand_purple));
    }

    private void cancelTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    // ── Handing it in ──────────────────────────────────────────────────────

    /**
     * Submits, asking first if anything was left blank.
     *
     * <p>A blank is allowed — the paper is the user's to hand in — but it's worth one question,
     * because leaving one behind while navigating is exactly the mistake this screen's free
     * movement makes easy.
     */
    private void confirmSubmit() {
        if (session == null) return;
        int blanks = session.unanswered();
        if (blanks == 0) {
            showResults(false);
            return;
        }

        cancelTimer(); // the clock must not run out while the user is reading the question
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.quiz_submit_unanswered_title)
                .setMessage(getResources().getQuantityString(
                        R.plurals.quiz_submit_unanswered_message, blanks, blanks))
                .setPositiveButton(R.string.action_submit_anyway,
                        (dialog, which) -> showResults(false))
                .setNegativeButton(R.string.action_keep_answering,
                        (dialog, which) -> resumeTimer())
                .setOnCancelListener(dialog -> resumeTimer())
                .show();
    }

    /** Picks the clock back up where it was paused — the time spent in a dialog is the app's
     *  question, not the user's, so it isn't charged for. */
    private void resumeTimer() {
        if (session == null || attemptClosed || remainingMs <= 0) return;
        startTimer(remainingMs);
    }

    private void showResults(boolean timedOut) {
        cancelTimer();
        closeAttempt(true);

        int score = session.score();
        int total = session.total();
        resultsTitle.setText(timedOut ? R.string.quiz_time_up_title : R.string.quiz_summary_title);
        resultsSummary.setText(getString(R.string.quiz_summary_format, score, total,
                Math.round(score * 100f / total)));
        resultsAdapter.submit(session.results());

        resultsRecycler.setVisibility(View.VISIBLE);
        retakeButton.setVisibility(View.VISIBLE);
        doneButton.setText(R.string.action_quiz_done);
        showPanel();
    }

    /** The note lost too many Q&amp;A blocks to build a quiz from. No attempt was ever opened. */
    private void showUnavailable() {
        cancelTimer();
        resultsTitle.setText(R.string.quiz_unavailable_title);
        resultsSummary.setText(getString(R.string.quiz_not_enough_qa_message,
                QuizRules.MIN_QA_BLOCKS));
        resultsRecycler.setVisibility(View.GONE);
        retakeButton.setVisibility(View.GONE);
        doneButton.setText(R.string.flashcards_action_back);
        showPanel();
    }

    private void showPanel() {
        questionScroll.setVisibility(View.GONE);
        navRow.setVisibility(View.GONE);
        submitButton.setVisibility(View.GONE);
        indicatorScroll.setVisibility(View.GONE);
        // The header keeps its back arrow but loses the clock: there is nothing left to run out.
        timerText.setVisibility(View.GONE);
        timerProgress.setVisibility(View.GONE);
        resetWarningState();
        positionText.setText("");
        resultsPanel.setVisibility(View.VISIBLE);
    }

    /**
     * Writes the attempt's outcome exactly once.
     *
     * <p>The repository only updates rows still marked in progress, so a second call can't reopen a
     * finished attempt — but the flag keeps the common case from making the write at all.
     *
     * <p>Leaving in the instant before the attempt's row comes back is the one case this can't
     * write: the row is already inserted (the disk executor is single-threaded, so it went first),
     * it just has no id here yet. It stays in progress and the stale sweep retires it as abandoned,
     * which is what it was.
     */
    private void closeAttempt(boolean completed) {
        if (attemptId == null || attemptClosed || session == null) return;
        attemptClosed = true;
        quizRepository.finishAttempt(attemptId, session.score(), session.answered(), completed,
                session.results(), null);
    }

    private void confirmLeave() {
        if (session == null || attemptClosed) {
            leave();
            return;
        }
        // The clock keeps running behind a dialog otherwise, and the run can expire while the user
        // is deciding whether to stay.
        cancelTimer();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.quiz_leave_title)
                .setMessage(R.string.quiz_leave_message)
                .setPositiveButton(R.string.action_leave_quiz, (dialog, which) -> {
                    closeAttempt(false);
                    leave();
                })
                .setNegativeButton(R.string.action_keep_going, (dialog, which) -> resumeTimer())
                .setOnCancelListener(dialog -> resumeTimer())
                .show();
    }

    @Override
    public void onStop() {
        super.onStop();
        cancelTimer();
        // A timed quiz can't be paused: whatever happens off-screen, the clock was going to run
        // down. Rather than resume into a run that's already invalid, the attempt is banked as
        // abandoned with what was answered — which is exactly what the history shows.
        if (session != null && !attemptClosed) {
            closeAttempt(false);
            abandonedWhileAway = true;
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // Done here rather than in onStop: navigating away from a fragment that is itself being
        // stopped is how you end up navigating twice, or into a controller that has already moved.
        if (abandonedWhileAway) {
            abandonedWhileAway = false;
            leave();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelTimer();
    }

    private void leave() {
        NavHostFragment.findNavController(this).navigateUp();
    }
}
