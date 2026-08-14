package mse.quill.ui.quiz;

import java.util.Collections;
import java.util.List;

/**
 * One generated multiple-choice question.
 *
 * <p>The texts are Markdown, as they are on a flashcard — an answer that was bolded or bulleted in
 * the note should read that way as an option too.
 *
 * <p>Immutable, and generated per attempt rather than stored: the option order is part of the
 * question, and a quiz that remembered it would be the same quiz twice.
 */
public final class QuizQuestion {

    /** The id of the Q&amp;A block this came from — the same id a flashcard hangs its history off. */
    public final String sourceId;
    public final String prompt;
    public final List<String> options;
    public final int correctIndex;

    QuizQuestion(String sourceId, String prompt, List<String> options, int correctIndex) {
        this.sourceId = sourceId;
        this.prompt = prompt;
        this.options = Collections.unmodifiableList(options);
        this.correctIndex = correctIndex;
    }

    public String correctOption() {
        return options.get(correctIndex);
    }

    public boolean isCorrect(int selectedIndex) {
        return selectedIndex == correctIndex;
    }
}
