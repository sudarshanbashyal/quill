package mse.quill.onboarding;

/**
 * The words and the drawing {@link SampleData} puts into a new Quill.
 *
 * <p>Held here rather than in {@code strings.xml} because none of it is interface text: the moment
 * it is written it becomes the user's own notes, editable and deletable like anything else they
 * typed. Translating a note after the fact would mean rewriting rows the user may have edited,
 * which is not something a string resource can do. The screen that *reports* this content is
 * localised in the usual way — see {@code activity_welcome.xml}.
 *
 * <p>The content is chosen to be worth keeping rather than to be a placeholder. It explains Quill
 * (there is no other manual), and the study-technique cards are real advice, so a user who keeps
 * them has five flashcards worth reviewing rather than five rows of "Sample question 1".
 */
final class SampleContent {

    private SampleContent() {}

    static final String COLLECTION_NAME = "Study Skills";

    static final String WELCOME_TITLE = "Welcome to Quill";
    static final String TECHNIQUES_TITLE = "Five study techniques";
    static final String SCRATCH_TITLE = "Quick capture";
    static final String WHITEBOARD_TITLE = "The forgetting curve";

    /**
     * The pinned note. {@code %1$s} is the sample whiteboard's id — the embed line is how a board
     * gets into a note, and writing it here is what makes the sketch appear inside this note
     * rather than only in Home's whiteboard row.
     */
    static final String WELCOME_MARKDOWN =
            "Everything you study, in one place. Here is what the tabs at the bottom do.\n"
                    + "\n"
                    + "## Notes\n"
                    + "Write here. The toolbar gives you headings, lists and **bold**. Notes can be"
                    + " grouped into collections — this one lives in _" + COLLECTION_NAME + "_.\n"
                    + "\n"
                    + "## Flashcards\n"
                    + "Add a question-and-answer block to any note and it becomes a card you can"
                    + " review. The note next door has five of them.\n"
                    + "\n"
                    + "## Quizzes\n"
                    + "The same blocks make a quiz, scored and kept so you can see whether you are"
                    + " getting better.\n"
                    + "\n"
                    + "## Whiteboards\n"
                    + "For the things sentences are bad at. Here is one — tap it to draw.\n"
                    + "\n"
                    + "![whiteboard](quill://whiteboard/%1$s)\n"
                    + "\n"
                    + "_This is sample content. Long-press any note or collection to delete it._";

    /** Five Q&amp;A blocks, which become five flashcards and a five-question quiz. */
    static final String TECHNIQUES_MARKDOWN =
            "Five things that reliably work, as question-and-answer blocks — which is to say, as"
                    + " flashcards.\n"
                    + "\n"
                    + qa("What is spaced repetition?",
                        "Reviewing something at growing intervals — a day, then three, then a week"
                                + " — so each review lands just before you would have forgotten it.")
                    + "\n"
                    + qa("What is active recall?",
                        "Answering from memory before you check. Retrieving the answer is what"
                                + " builds the memory; rereading mostly builds recognition.")
                    + "\n"
                    + qa("Why interleave subjects instead of blocking them?",
                        "Mixing related topics in one session forces you to work out which method a"
                                + " question needs, which is exactly what an exam asks for.")
                    + "\n"
                    + qa("What does the Pomodoro technique do?",
                        "Splits work into focused blocks of about 25 minutes with short breaks, so"
                                + " attention is spent in stretches you can actually sustain.")
                    + "\n"
                    + qa("What is elaboration?",
                        "Explaining an idea in your own words and tying it to something you already"
                                + " know, which gives your memory more than one route back to it.");

    /** A note filed nowhere, so Home shows what an unfiled note looks like. */
    static final String SCRATCH_MARKDOWN =
            "Notes you have not filed yet live here, outside any collection.\n"
                    + "\n"
                    + "- Tap **+** on Home to start one\n"
                    + "- Long-press a note to pin, move or delete it\n"
                    + "- Everything stays on this device — there is no account and no server";

    /**
     * One Q&amp;A block in the note format: a fence, the question, a divider, the answer.
     *
     * <p>No id on the fence line. {@code NoteDocument} mints one while parsing and the first save
     * writes it back into the document, so the block keeps a stable identity from then on — which
     * is what lets a flashcard survive its question being edited (see
     * {@code FlashcardRepository.syncFromNote}).
     */
    private static String qa(String question, String answer) {
        return "```quill-qa\n" + question + "\n---\n" + answer + "\n```\n";
    }
}
