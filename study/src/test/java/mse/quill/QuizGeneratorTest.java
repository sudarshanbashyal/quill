package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import mse.quill.study.quiz.QuizGenerator;
import mse.quill.study.quiz.QuizQuestion;
import mse.quill.study.quiz.QuizRules;

/** What a note's Q&A blocks turn into: one question each, with wrong options taken from the rest. */
public class QuizGeneratorTest {

    private static List<QuizGenerator.QaPair> pairs(int count) {
        List<QuizGenerator.QaPair> pairs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pairs.add(new QuizGenerator.QaPair("id" + i, "question " + i, "answer " + i));
        }
        return pairs;
    }

    private static List<QuizQuestion> generate(List<QuizGenerator.QaPair> pairs) {
        return QuizGenerator.generate(pairs, new Random(42));
    }

    @Test
    public void eachBlockBecomesOneQuestion() {
        List<QuizQuestion> questions = generate(pairs(7));

        assertEquals(7, questions.size());
        Set<String> sources = new HashSet<>();
        for (QuizQuestion question : questions) sources.add(question.sourceId);
        assertEquals("every block should be asked exactly once", 7, sources.size());
    }

    @Test
    public void tooFewBlocksGenerateNothing() {
        assertTrue(generate(pairs(QuizRules.MIN_QA_BLOCKS - 1)).isEmpty());
        assertEquals(QuizRules.MIN_QA_BLOCKS, generate(pairs(QuizRules.MIN_QA_BLOCKS)).size());
    }

    @Test
    public void everyQuestionHasOneCorrectOptionAndTheRestAreOtherBlocksAnswers() {
        List<QuizGenerator.QaPair> pairs = pairs(6);
        List<QuizQuestion> questions = generate(pairs);

        Set<String> allAnswers = new HashSet<>();
        for (QuizGenerator.QaPair pair : pairs) allAnswers.add(pair.answer);

        for (QuizQuestion question : questions) {
            assertEquals(QuizRules.OPTIONS_PER_QUESTION, question.options.size());

            String expected = "answer " + question.sourceId.substring(2);
            assertEquals(expected, question.correctOption());

            Set<String> distinct = new HashSet<>(question.options);
            assertEquals("options must not repeat", question.options.size(), distinct.size());
            for (String option : question.options) {
                assertTrue("distractors come from the note's own answers",
                        allAnswers.contains(option));
            }
        }
    }

    @Test
    public void correctOptionIsNotAlwaysInTheSamePlace() {
        Set<Integer> positions = new HashSet<>();
        for (QuizQuestion question : generate(pairs(12))) {
            positions.add(question.correctIndex);
        }
        assertTrue("a fixed position would make the quiz answerable without reading it",
                positions.size() > 1);
    }

    @Test
    public void answersRepeatedAcrossBlocksAreNeverOfferedTwice() {
        List<QuizGenerator.QaPair> pairs = new ArrayList<>();
        pairs.add(new QuizGenerator.QaPair("a", "What is 1+1?", "Two"));
        pairs.add(new QuizGenerator.QaPair("b", "What is 4-2?", "two"));   // same answer, cased
        pairs.add(new QuizGenerator.QaPair("c", "What is 6/3?", " TWO "));  // and padded
        pairs.add(new QuizGenerator.QaPair("d", "What is 1+2?", "Three"));
        pairs.add(new QuizGenerator.QaPair("e", "What is 2+2?", "Four"));

        for (QuizQuestion question : generate(pairs)) {
            Set<String> normalised = new HashSet<>();
            for (String option : question.options) {
                assertTrue("an option that is the correct answer in disguise makes the question "
                        + "unanswerable", normalised.add(option.trim().toLowerCase()));
            }
        }
    }

    @Test
    public void questionOrderIsShuffled() {
        List<QuizGenerator.QaPair> pairs = pairs(10);
        List<String> firstRun = sourceOrder(QuizGenerator.generate(pairs, new Random(1)));
        List<String> secondRun = sourceOrder(QuizGenerator.generate(pairs, new Random(2)));

        assertNotEquals("two attempts should not walk the note top to bottom the same way",
                firstRun, secondRun);
    }

    private static List<String> sourceOrder(List<QuizQuestion> questions) {
        List<String> order = new ArrayList<>();
        for (QuizQuestion question : questions) order.add(question.sourceId);
        return order;
    }
}
