package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import mse.quill.study.quiz.QuizGenerator;
import mse.quill.study.quiz.QuizQuestion;
import mse.quill.study.quiz.QuizSession;

/** The answer sheet: free movement between questions, changeable answers, and marking at the end. */
public class QuizSessionTest {

    /** Questions are generated rather than hand-built — a {@link QuizQuestion} is only ever made by
     *  the generator, and a test double would be free to be a shape the app never produces. */
    private static List<QuizQuestion> questions(int count) {
        List<QuizGenerator.QaPair> pairs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pairs.add(new QuizGenerator.QaPair("id" + i, "question " + i, "answer " + i));
        }
        return QuizGenerator.generate(pairs, new Random(7));
    }

    private static QuizSession sessionOf(int count) {
        return new QuizSession(questions(count));
    }

    private static void answerCorrectly(QuizSession session) {
        session.select(session.current().correctIndex);
    }

    private static void answerWrongly(QuizSession session) {
        QuizQuestion question = session.current();
        session.select((question.correctIndex + 1) % question.options.size());
    }

    @Test
    public void movesBothWaysAndStopsAtTheEnds() {
        QuizSession session = sessionOf(5);

        assertFalse(session.hasPrevious());
        session.previous();
        assertEquals("nothing before the first question", 0, session.currentIndex());

        for (int i = 0; i < 4; i++) session.next();
        assertEquals(4, session.currentIndex());
        assertFalse(session.hasNext());
        session.next();
        assertEquals("nothing after the last question", 4, session.currentIndex());

        session.previous();
        assertEquals(3, session.currentIndex());
    }

    @Test
    public void jumpsStraightToAQuestion() {
        QuizSession session = sessionOf(6);

        session.goTo(4);
        assertEquals(4, session.currentIndex());
        assertEquals(5, session.position());

        session.goTo(-1);
        session.goTo(6);
        assertEquals("an out-of-range jump goes nowhere at all", 4, session.currentIndex());
    }

    @Test
    public void questionsCanBeLeftBlankAndReturnedTo() {
        QuizSession session = sessionOf(5);

        session.next();          // leave question 1 blank
        answerCorrectly(session);
        assertFalse(session.isAnswered(0));
        assertTrue(session.isAnswered(1));
        assertEquals(4, session.unanswered());

        session.goTo(0);
        answerCorrectly(session);
        assertTrue("coming back to a blank must be able to fill it", session.isAnswered(0));
        assertEquals(2, session.answered());
        assertEquals(2, session.score());
    }

    @Test
    public void ananswerCanBeChangedAndCleared() {
        QuizSession session = sessionOf(5);
        QuizQuestion first = session.current();
        int wrong = (first.correctIndex + 1) % first.options.size();

        session.select(wrong);
        assertEquals(wrong, session.currentSelection());
        assertEquals(0, session.score());

        session.select(first.correctIndex);
        assertEquals("changing an answer replaces it", first.correctIndex,
                session.currentSelection());
        assertEquals(1, session.score());

        session.select(first.correctIndex);
        assertEquals("tapping the chosen option again undoes it",
                QuizSession.NO_SELECTION, session.currentSelection());
        assertFalse(session.isAnswered(0));
    }

    @Test
    public void blanksAreMarkedWrongRatherThanExcluded() {
        QuizSession session = sessionOf(5);

        answerCorrectly(session);
        session.next();
        answerCorrectly(session);
        // three left blank

        assertEquals(2, session.score());
        assertEquals(2, session.answered());
        assertEquals(3, session.unanswered());
        assertEquals("the whole paper is marked, not just the filled-in part",
                5, session.results().size());

        QuizSession.Result blank = session.results().get(4);
        assertFalse(blank.wasAnswered());
        assertFalse(blank.wasCorrect());
        assertNull(blank.selectedOption());
    }

    @Test
    public void scoreCountsCorrectAnswersAcrossTheWholePaper() {
        QuizSession session = sessionOf(5);

        answerCorrectly(session);
        session.next();
        answerWrongly(session);
        session.next();
        answerCorrectly(session);
        session.next();
        answerCorrectly(session);
        session.next();
        answerWrongly(session);

        assertEquals(3, session.score());
        assertEquals(5, session.answered());
        assertEquals(0, session.unanswered());
    }

    @Test
    public void resultsRecordWhatWasPickedAgainstWhatWasRight() {
        QuizSession session = sessionOf(5);
        QuizQuestion first = session.current();
        int wrongIndex = (first.correctIndex + 1) % first.options.size();

        session.select(wrongIndex);

        QuizSession.Result result = session.results().get(0);
        assertTrue(result.wasAnswered());
        assertFalse(result.wasCorrect());
        assertEquals(first.options.get(wrongIndex), result.selectedOption());
        assertEquals(first.correctOption(), result.question.correctOption());
    }

    @Test
    public void anAbandonedRunScoresWhatWasAnsweredSoFar() {
        QuizSession session = sessionOf(6);

        answerCorrectly(session);
        session.next();
        answerCorrectly(session);
        session.next();
        answerWrongly(session);

        assertEquals("what gets banked when the user walks out", 2, session.score());
        assertEquals(3, session.answered());
    }
}
