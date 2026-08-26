package mse.quill.data;

import android.content.Context;

/**
 * Where a screen gets its data layer.
 *
 * <p>One line instead of {@code new NoteRepository(context)}, and — the reason it exists — one
 * place that decides what a caller actually receives. Ten fragments used to {@code new} their
 * concrete repositories directly ({@code NoteEditorFragment} constructed six), which left nothing
 * to substitute and no place to put a decision about lifetime or caching if one is ever wanted.
 *
 * <p>Deliberately <b>not</b> a dependency-injection framework, and deliberately not extended to
 * every repository. Only notes and flashcards have interfaces so far, because those two are the
 * ones whose logic most deserves JVM tests — Markdown round-tripping, orphan cleanup, FTS index
 * maintenance, orphan marking and due counts. The rest keep their concrete constructors until
 * something makes a case for more, per {@code memory/refactoring_plan.md} R6.
 */
public final class Repositories {

    private Repositories() {}

    public static NoteStore notes(Context context) {
        return new NoteRepository(context);
    }

    public static FlashcardStore flashcards(Context context) {
        return new FlashcardRepository(context);
    }
}
