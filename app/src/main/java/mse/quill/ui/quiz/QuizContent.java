package mse.quill.ui.quiz;

import java.util.ArrayList;
import java.util.List;

import mse.quill.data.FlashcardRepository;
import mse.quill.data.serialization.MarkdownSerializer;
import mse.quill.data.model.NoteSegment;
import mse.quill.data.model.QaSegment;

/**
 * The bridge from a loaded note to the strings a quiz is generated from.
 *
 * <p>Reuses {@link FlashcardRepository#reviewableQa} rather than restating what a usable Q&amp;A
 * block is: "both halves say something" is one rule, and a note that can make flashcards but not
 * quiz questions (or the reverse) would be a bug in one of two copies of it.
 *
 * <p>Markdown, not plain text — an answer that was bolded in the note should be bold as an option,
 * exactly as it is on a flashcard.
 */
public final class QuizContent {

    private QuizContent() {}

    public static List<QuizGenerator.QaPair> pairsFrom(List<NoteSegment> segments) {
        List<QuizGenerator.QaPair> pairs = new ArrayList<>();
        for (QaSegment qa : FlashcardRepository.reviewableQa(segments)) {
            pairs.add(new QuizGenerator.QaPair(qa.id,
                    MarkdownSerializer.toMarkdown(qa.question),
                    MarkdownSerializer.toMarkdown(qa.answer)));
        }
        return pairs;
    }
}
