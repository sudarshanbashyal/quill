package mse.quill.study.quiz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mse.quill.study.review.ReviewSession;

/**
 * One sitting of a quiz: the questions, the answer sheet, and where the user is looking.
 *
 * <p>This is a <em>paper</em>, not a conveyor belt. Every question can be visited in any order, as
 * often as wanted; an answer can be changed, and leaving one blank to come back to it is a normal
 * thing to do rather than a forfeit. Nothing is graded until the whole thing is handed in — which
 * is also why a run can be submitted with blanks still on it.
 *
 * <p>Contrast {@code ReviewSession}, where a card is answered once and a miss puts it back in the
 * queue. That's practice, and being told the answer is the point. Here being told anything before
 * the end would change what the score measures.
 *
 * <p>Plain Java, no Android: the interesting behaviour is the answer sheet, and it's testable on
 * its own.
 */
public class QuizSession {

    /** No option chosen. Marked wrong at the end — the question was put, it just wasn't answered. */
    public static final int NO_SELECTION = -1;

    private final List<QuizQuestion> questions;
    private final int[] selections;
    private int index;

    public QuizSession(List<QuizQuestion> questions) {
        this.questions = new ArrayList<>(questions);
        this.selections = new int[this.questions.size()];
        Arrays.fill(this.selections, NO_SELECTION);
    }

    // ── Where the user is ──────────────────────────────────────────────────

    /** The question on screen. Null only for the degenerate empty quiz. */
    public QuizQuestion current() {
        return questions.isEmpty() ? null : questions.get(index);
    }

    public int currentIndex() {
        return index;
    }

    /** 1-based, for "Question 3 of 7". */
    public int position() {
        return index + 1;
    }

    public int total() {
        return questions.size();
    }

    public QuizQuestion questionAt(int questionIndex) {
        return questions.get(questionIndex);
    }

    public boolean hasNext() {
        return index < questions.size() - 1;
    }

    public boolean hasPrevious() {
        return index > 0;
    }

    /** Moves to a question by index; out-of-range jumps are ignored rather than clamped, since a
     *  clamp would quietly send the user somewhere they didn't ask to go. */
    public void goTo(int questionIndex) {
        if (questionIndex < 0 || questionIndex >= questions.size()) return;
        index = questionIndex;
    }

    public void next() {
        if (hasNext()) index++;
    }

    public void previous() {
        if (hasPrevious()) index--;
    }

    // ── The answer sheet ───────────────────────────────────────────────────

    /**
     * Records (or changes) the answer to the question on screen.
     *
     * <p>Passing the option already selected clears it: on a sheet that can be revisited, the only
     * other way to undo a mis-tap would be to leave it wrong.
     */
    public void select(int optionIndex) {
        if (questions.isEmpty()) return;
        selections[index] = selections[index] == optionIndex ? NO_SELECTION : optionIndex;
    }

    public int selectionAt(int questionIndex) {
        return selections[questionIndex];
    }

    public int currentSelection() {
        return questions.isEmpty() ? NO_SELECTION : selections[index];
    }

    public boolean isAnswered(int questionIndex) {
        return selections[questionIndex] != NO_SELECTION;
    }

    /** Questions with something on them — what the attempt records as answered. */
    public int answered() {
        int answered = 0;
        for (int selection : selections) {
            if (selection != NO_SELECTION) answered++;
        }
        return answered;
    }

    /** What the submit confirmation counts. Zero means the sheet is full. */
    public int unanswered() {
        return questions.size() - answered();
    }

    // ── Marking ────────────────────────────────────────────────────────────

    /** Correct answers across the whole paper. Blanks are wrong, not excluded. */
    public int score() {
        int score = 0;
        for (int i = 0; i < questions.size(); i++) {
            if (questions.get(i).isCorrect(selections[i])) score++;
        }
        return score;
    }

    /** The marked paper: every question, what was picked, what was right. */
    public List<Result> results() {
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            results.add(new Result(questions.get(i), selections[i]));
        }
        return results;
    }

    /** One marked question. */
    public static final class Result {
        public final QuizQuestion question;
        /** {@link #NO_SELECTION} if the question was left blank. */
        public final int selectedIndex;

        /** A result read back from a finished attempt — see {@link QuizQuestion#restored}. */
        public static Result restored(QuizQuestion question, int selectedIndex) {
            return new Result(question, selectedIndex);
        }

        Result(QuizQuestion question, int selectedIndex) {
            this.question = question;
            this.selectedIndex = selectedIndex;
        }

        public boolean wasCorrect() {
            return question.isCorrect(selectedIndex);
        }

        public boolean wasAnswered() {
            return selectedIndex != NO_SELECTION;
        }

        public String selectedOption() {
            return wasAnswered() ? question.options.get(selectedIndex) : null;
        }
    }
}
