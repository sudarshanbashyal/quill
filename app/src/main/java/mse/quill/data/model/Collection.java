package mse.quill.data.model;

public class Collection {
    public String id;
    public String name;
    public int color;
    public long createdAt;
    public boolean biometricLocked;

    /** Query-derived, not stored columns. */
    public int noteCount;
    /** Flashcards and quizzes belonging to this collection's notes, for the card's summary line. */
    public int flashcardCount;
    public int quizCount;
    public long lastActivityAt;
}
