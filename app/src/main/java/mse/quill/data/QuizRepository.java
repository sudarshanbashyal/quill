package mse.quill.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import mse.quill.ui.quiz.QuizQuestion;
import mse.quill.ui.quiz.QuizSession;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import mse.quill.data.model.Quiz;
import mse.quill.data.model.QuizAttempt;
import mse.quill.ui.quiz.QuizRules;

/**
 * Quizzes and the history of sitting them.
 *
 * <p>Deliberately holds no questions. A quiz row says "this note is a quiz"; the questions are
 * generated from the note's Q&amp;A blocks each time an attempt starts (see {@code QuizGenerator}),
 * which is what keeps a quiz honest against an edited note without any syncing of its own — the
 * problem the flashcard side has to solve with {@code source_segment_id} doesn't arise here.
 *
 * <p>Follows the callback-based async pattern of the other repositories: work on {@code diskIO},
 * results delivered on the main thread.
 */
public class QuizRepository {

    public interface OnQuizReady { void onReady(Quiz quiz); }
    public interface OnQuizzesLoaded { void onLoaded(List<Quiz> quizzes); }
    public interface OnAttemptsLoaded { void onLoaded(List<QuizAttempt> attempts); }
    public interface OnAttemptStarted { void onStarted(String attemptId); }
    public interface OnExists { void onResult(boolean exists); }

    private final AppDatabase appDatabase;
    private final AppExecutors executors;

    public QuizRepository(Context context) {
        this.appDatabase = AppDatabase.getInstance(context.getApplicationContext());
        this.executors = AppExecutors.getInstance();
    }

    /**
     * Returns the note's quiz, creating it the first time.
     *
     * <p>"Make quiz" is idempotent: running it again on a note that already has one opens the
     * existing quiz rather than starting a second history for the same content.
     */
    public void ensureForNote(String noteId, OnQuizReady cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            Quiz quiz = loadByNoteSync(db, noteId);
            if (quiz == null) {
                quiz = new Quiz();
                quiz.id = UUID.randomUUID().toString();
                quiz.noteId = noteId;
                quiz.createdAt = System.currentTimeMillis();

                ContentValues cv = new ContentValues();
                cv.put("id", quiz.id);
                cv.put("note_id", quiz.noteId);
                cv.put("created_at", quiz.createdAt);
                db.insert("quizzes", null, cv);
            }
            Quiz result = quiz;
            if (cb != null) executors.mainThread(() -> cb.onReady(result));
        });
    }

    /** Whether this note already has a quiz — what decides between "Make quiz" and "Open quiz". */
    public void existsForNote(String noteId, OnExists cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM quizzes WHERE note_id = ?",
                    new String[]{noteId});
            boolean exists = false;
            try {
                if (c.moveToFirst()) exists = c.getInt(0) > 0;
            } finally {
                c.close();
            }
            boolean result = exists;
            if (cb != null) executors.mainThread(() -> cb.onResult(result));
        });
    }

    /**
     * Every quiz with its attempt summary.
     *
     * <p>Never-taken quizzes lead — they're the ones with something to do — and the rest follow by
     * how recently they were sat. Notes in the trash drop out, the same way their decks do.
     */
    public void loadQuizzes(OnQuizzesLoaded cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            abandonStaleSync(db);

            // A quiz row carries its note's title, so a shut collection's quizzes would list those
            // titles on the Quizzes tab. Sitting one isn't possible either way — the session loads
            // the note through NoteRepository, which refuses a locked one — but the list is a leak
            // on its own.
            Set<String> lockedIds = NoteCrypto.lockedCollectionIds(db);
            Set<String> hidden = NoteCrypto.hiddenOf(lockedIds);
            List<String> args = new ArrayList<>();
            args.add(QuizAttempt.STATUS_COMPLETED);
            args.add(QuizAttempt.STATUS_COMPLETED);
            args.addAll(hidden);

            List<Quiz> quizzes = new ArrayList<>();
            Cursor c = db.rawQuery(SELECT_QUIZ_SUMMARY +
                            "WHERE n.deleted_at IS NULL " +
                            NoteCrypto.hiddenClause(hidden) +
                            GROUP_QUIZ_SUMMARY +
                            "ORDER BY (CASE WHEN MAX(a.started_at) IS NULL THEN 0 ELSE 1 END), " +
                            "MAX(a.started_at) DESC, q.created_at DESC",
                    args.toArray(new String[0]));
            try {
                while (c.moveToNext()) {
                    // An open collection's quiz still stores its title encrypted; readQuiz decrypts
                    // it, and drops the row if the key has gone since the collection was opened.
                    Quiz quiz = readQuiz(c, lockedIds);
                    if (quiz != null) quizzes.add(quiz);
                }
            } finally {
                c.close();
            }

            if (cb != null) executors.mainThread(() -> cb.onLoaded(quizzes));
        });
    }

    /**
     * One quiz by id, or null if it has been deleted from underneath the screen showing it — or if
     * its collection has been shut since, which both callers treat the same way and leave.
     */
    public void loadQuiz(String quizId, OnQuizReady cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            abandonStaleSync(db);

            Set<String> lockedIds = NoteCrypto.lockedCollectionIds(db);
            Set<String> hidden = NoteCrypto.hiddenOf(lockedIds);
            List<String> args = new ArrayList<>();
            args.add(QuizAttempt.STATUS_COMPLETED);
            args.add(QuizAttempt.STATUS_COMPLETED);
            args.add(quizId);
            args.addAll(hidden);

            Quiz quiz = null;
            Cursor c = db.rawQuery(SELECT_QUIZ_SUMMARY +
                            "WHERE q.id = ? " +
                            NoteCrypto.hiddenClause(hidden) +
                            GROUP_QUIZ_SUMMARY,
                    args.toArray(new String[0]));
            try {
                if (c.moveToNext()) quiz = readQuiz(c, lockedIds);
            } finally {
                c.close();
            }

            Quiz result = quiz;
            if (cb != null) executors.mainThread(() -> cb.onReady(result));
        });
    }

    /** A quiz's attempts, most recent first. */
    public void loadAttempts(String quizId, OnAttemptsLoaded cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            abandonStaleSync(db);

            List<QuizAttempt> attempts = new ArrayList<>();
            Cursor c = db.rawQuery(
                    "SELECT id, quiz_id, score, answered, total, status, started_at, finished_at " +
                            "FROM quiz_attempts WHERE quiz_id = ? ORDER BY started_at DESC",
                    new String[]{quizId});
            try {
                while (c.moveToNext()) {
                    QuizAttempt attempt = new QuizAttempt();
                    attempt.id = c.getString(0);
                    attempt.quizId = c.getString(1);
                    attempt.score = c.getInt(2);
                    attempt.answered = c.getInt(3);
                    attempt.total = c.getInt(4);
                    attempt.status = c.getString(5);
                    attempt.startedAt = c.getLong(6);
                    attempt.finishedAt = c.isNull(7) ? null : c.getLong(7);
                    attempts.add(attempt);
                }
            } finally {
                c.close();
            }

            if (cb != null) executors.mainThread(() -> cb.onLoaded(attempts));
        });
    }

    /**
     * Opens an attempt as the first question appears.
     *
     * <p>Recorded up front, not at the end, so an attempt that's walked out of still exists to be
     * marked abandoned — the alternative silently rewards giving up with a clean history.
     */
    public void startAttempt(String quizId, int total, OnAttemptStarted cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            String id = UUID.randomUUID().toString();

            ContentValues cv = new ContentValues();
            cv.put("id", id);
            cv.put("quiz_id", quizId);
            cv.put("score", 0);
            cv.put("answered", 0);
            cv.put("total", total);
            cv.put("status", QuizAttempt.STATUS_IN_PROGRESS);
            cv.put("started_at", System.currentTimeMillis());
            db.insert("quiz_attempts", null, cv);

            if (cb != null) executors.mainThread(() -> cb.onStarted(id));
        });
    }

    /** Closes an attempt: completed if every question was answered, abandoned if it was left. */
    /**
     * Closes an attempt and files the paper with it.
     *
     * <p>{@code results} is what makes an attempt reopenable later. It is written for abandoned
     * attempts too: walking out of a quiz half done is a thing worth being able to look at again,
     * and the alternative — keeping answers only for completed runs — would make the history lie
     * about which questions were even reached.
     */
    public void finishAttempt(String attemptId, int score, int answered, boolean completed,
                              List<QuizSession.Result> results, Runnable onDone) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            saveAnswersSync(db, attemptId, results);
            ContentValues cv = new ContentValues();
            cv.put("score", score);
            cv.put("answered", answered);
            cv.put("status", completed
                    ? QuizAttempt.STATUS_COMPLETED : QuizAttempt.STATUS_ABANDONED);
            cv.put("finished_at", System.currentTimeMillis());
            // Only while still in progress: leaving the results screen must not reopen and re-close
            // an attempt that was already finished a moment earlier.
            db.update("quiz_attempts", cv, "id = ? AND status = ?",
                    new String[]{attemptId, QuizAttempt.STATUS_IN_PROGRESS});
            if (onDone != null) executors.mainThread(onDone);
        });
    }

    /**
     * Replaces this attempt's stored answers.
     *
     * <p>Replaces rather than appends, so a re-close of the same attempt — which the status guard
     * above already makes a no-op for the score — cannot leave two papers stapled together.
     */
    private void saveAnswersSync(SQLiteDatabase db, String attemptId,
                                 List<QuizSession.Result> results) {
        if (results == null) return;
        db.beginTransaction();
        try {
            db.delete("quiz_attempt_answers", "attempt_id = ?", new String[]{attemptId});
            for (int i = 0; i < results.size(); i++) {
                QuizSession.Result result = results.get(i);
                ContentValues cv = new ContentValues();
                cv.put("id", UUID.randomUUID().toString());
                cv.put("attempt_id", attemptId);
                cv.put("position", i);
                cv.put("source_id", result.question.sourceId);
                cv.put("prompt", result.question.prompt);
                cv.put("options", new JSONArray(result.question.options).toString());
                cv.put("correct_index", result.question.correctIndex);
                cv.put("selected_index", result.selectedIndex);
                db.insert("quiz_attempt_answers", null, cv);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public interface OnAnswersLoaded { void onLoaded(List<QuizSession.Result> results); }

    /**
     * The marked paper for a past attempt, in the order it was sat.
     *
     * <p>Comes back empty for an attempt taken before answers were stored, which the caller shows
     * as "this one wasn't kept" rather than as an error — the score on the row beside it is still
     * true.
     */
    public void loadAttemptAnswers(String attemptId, OnAnswersLoaded cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            List<QuizSession.Result> results = new ArrayList<>();
            try (Cursor c = db.rawQuery(
                    "SELECT source_id, prompt, options, correct_index, selected_index "
                            + "FROM quiz_attempt_answers WHERE attempt_id = ? ORDER BY position ASC",
                    new String[]{attemptId})) {
                while (c.moveToNext()) {
                    List<String> options = new ArrayList<>();
                    try {
                        JSONArray stored = new JSONArray(c.isNull(2) ? "[]" : c.getString(2));
                        for (int i = 0; i < stored.length(); i++) options.add(stored.getString(i));
                    } catch (JSONException e) {
                        Log.w("QuizRepository", "unreadable stored options for attempt " + attemptId, e);
                        continue;
                    }
                    QuizQuestion question = QuizQuestion.restored(
                            c.isNull(0) ? null : c.getString(0),
                            c.isNull(1) ? "" : c.getString(1),
                            options,
                            c.getInt(3));
                    results.add(QuizSession.Result.restored(question, c.getInt(4)));
                }
            }
            if (cb != null) executors.mainThread(() -> cb.onLoaded(results));
        });
    }

    /**
     * Deletes a quiz and everything ever scored on it.
     *
     * <p>A hard delete, like a flashcard deck's, and for the same reason: the note's Q&amp;A blocks
     * are untouched, so the quiz can simply be made again — what's actually gone is the history,
     * which is what the confirmation warns about.
     */
    public void deleteQuiz(String quizId, Runnable onDeleted) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete("quiz_attempt_answers",
                        "attempt_id IN (SELECT id FROM quiz_attempts WHERE quiz_id = ?)",
                        new String[]{quizId});
                db.delete("quiz_attempts", "quiz_id = ?", new String[]{quizId});
                db.delete("quizzes", "id = ?", new String[]{quizId});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            if (onDeleted != null) executors.mainThread(onDeleted);
        });
    }

    // ── Query helpers (must run on the diskIO executor) ─────────────────────

    /**
     * The summary columns every quiz listing shares. Both {@code ?} placeholders are the "completed"
     * status: attempts that were abandoned still count as having taken the quiz (they set the "last
     * taken" time) but must not contribute a score to the best-of.
     */
    private static final String SELECT_QUIZ_SUMMARY =
            "SELECT q.id, q.note_id, n.title, q.created_at, " +
                    "SUM(CASE WHEN a.status = ? THEN 1 ELSE 0 END), " +
                    "MAX(CASE WHEN a.status = ? AND a.total > 0 " +
                    "THEN (a.score * 100) / a.total ELSE NULL END), " +
                    "MAX(a.started_at), n.collection_id, " +
                    // Only so an unnamed note's quiz can be titled with the same dated fallback the
                    // rest of the app shows it under.
                    "n.created_at " +
                    "FROM quizzes q JOIN notes n ON n.id = q.note_id " +
                    "LEFT JOIN quiz_attempts a ON a.quiz_id = q.id ";

    /** The GROUP BY every listing off {@link #SELECT_QUIZ_SUMMARY} shares. */
    private static final String GROUP_QUIZ_SUMMARY =
            "GROUP BY q.id, q.note_id, n.title, n.collection_id, n.created_at, q.created_at ";

    /**
     * @return null if the note's title is encrypted and can no longer be read — the collection is
     *     shut again by then, and a quiz named in ciphertext is worse than one that isn't listed.
     */
    private static Quiz readQuiz(Cursor c, Set<String> lockedIds) {
        String collectionId = c.isNull(7) ? null : c.getString(7);
        String title = c.isNull(2)
                ? "" : NoteCrypto.titleForDisplay(lockedIds, collectionId, c.getString(2));
        if (title == null) return null;

        Quiz quiz = new Quiz();
        quiz.id = c.getString(0);
        quiz.noteId = c.getString(1);
        quiz.noteTitle = title;
        quiz.createdAt = c.getLong(3);
        quiz.attempts = c.getInt(4);
        quiz.bestPercent = c.isNull(5) ? null : c.getInt(5);
        quiz.lastAttemptAt = c.isNull(6) ? null : c.getLong(6);
        quiz.noteCreatedAt = c.getLong(8);
        return quiz;
    }

    private Quiz loadByNoteSync(SQLiteDatabase db, String noteId) {
        Cursor c = db.rawQuery("SELECT id, note_id, created_at FROM quizzes WHERE note_id = ?",
                new String[]{noteId});
        try {
            if (!c.moveToFirst()) return null;
            Quiz quiz = new Quiz();
            quiz.id = c.getString(0);
            quiz.noteId = c.getString(1);
            quiz.createdAt = c.getLong(2);
            return quiz;
        } finally {
            c.close();
        }
    }

    /**
     * Retires attempts that were left open by something that never got to close them — a crash, or
     * the process being killed behind the quiz screen.
     *
     * <p>A quiz is strictly time-boxed, so "too old to still be running" is computable rather than
     * arbitrary: its questions can't have taken longer than {@code total × QUESTION_TIME_MS}, plus
     * a grace period for a screen that sat backgrounded mid-question. Their score is whatever had
     * been recorded, which for a killed attempt is 0 — nothing else is knowable.
     */
    private void abandonStaleSync(SQLiteDatabase db) {
        long now = System.currentTimeMillis();
        db.execSQL("UPDATE quiz_attempts SET status = ?, finished_at = started_at + total * ? " +
                        "WHERE status = ? AND started_at + total * ? + ? < ?",
                new Object[]{QuizAttempt.STATUS_ABANDONED, QuizRules.QUESTION_TIME_MS,
                        QuizAttempt.STATUS_IN_PROGRESS, QuizRules.QUESTION_TIME_MS,
                        QuizRules.ABANDON_GRACE_MS, now});
    }
}
