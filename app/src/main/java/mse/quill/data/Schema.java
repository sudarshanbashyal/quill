package mse.quill.data;

import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

/**
 * What a fresh Quill database looks like.
 *
 * <p>Split out of {@link AppDatabase} on 2026-08-28. That file was 748 lines holding three
 * different things — the singleton, this, and {@link Migrations} — which made "what does a new
 * install create?" and "what happened to installs from March?" the same question to read. They are
 * separate questions and now have separate files.
 *
 * <p>Nothing here is versioned or conditional. Every statement runs exactly once, on a database
 * that does not exist yet. Anything that has to cope with a database that already exists belongs
 * in {@link Migrations}, and adding a column here without also adding it there is the one mistake
 * this split makes easy — see {@code Migrations.ensureAdditiveSchema}.
 */
final class Schema {

    private Schema() {}

    /** Every table and index, for a database being created from nothing. */
    static void createAll(SQLiteDatabase db) {

        // ---------- Core content tables ----------

        db.execSQL("CREATE TABLE collections (" +
                "id TEXT PRIMARY KEY, " +
                "name TEXT, " +
                "color INTEGER, " +
                "created_at INTEGER, " +
                "biometric_locked INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE notes (" +
                "id TEXT PRIMARY KEY, " +
                "collection_id TEXT, " +
                "title TEXT, " +
                "content_blob BLOB, " +
                "created_at INTEGER, " +
                "updated_at INTEGER, " +
                "author_device_id TEXT, " +
                "vector_clock TEXT, " +
                "deleted_at INTEGER, " +
                "location_lat REAL, " +
                "location_lng REAL, " +
                "location_name TEXT, " +
                "pinned_at INTEGER, " +
                "FOREIGN KEY(collection_id) REFERENCES collections(id))");

        // note_id is nullable: a whiteboard can stand on its own (created from Home) as well as
        // belong to a note. title/updated_at exist so Home can list boards meaningfully — a board
        // has no body text to derive a preview or a timestamp from the way a note does.
        db.execSQL("CREATE TABLE whiteboards (" +
                "id TEXT PRIMARY KEY, " +
                "note_id TEXT, " +
                "title TEXT, " +
                "created_at INTEGER, " +
                "updated_at INTEGER, " +
                "background INTEGER DEFAULT 0, " +
                "FOREIGN KEY(note_id) REFERENCES notes(id))");

        db.execSQL("CREATE TABLE strokes (" +
                "id TEXT PRIMARY KEY, " +
                "whiteboard_id TEXT, " +
                "author_id TEXT, " +
                "tool INTEGER, " +
                "color INTEGER, " +
                "width REAL, " +
                "points_blob BLOB, " +
                "created_at INTEGER, " +
                "FOREIGN KEY(whiteboard_id) REFERENCES whiteboards(id))");

        db.execSQL("CREATE TABLE whiteboard_texts (" +
                "id TEXT PRIMARY KEY, " +
                "whiteboard_id TEXT, " +
                "author_id TEXT, " +
                "x REAL, " +
                "y REAL, " +
                "text TEXT, " +
                "color INTEGER, " +
                "size REAL, " +
                "created_at INTEGER, " +
                "FOREIGN KEY(whiteboard_id) REFERENCES whiteboards(id))");
        db.execSQL("CREATE INDEX idx_whiteboard_texts_whiteboard_id " +
                "ON whiteboard_texts(whiteboard_id)");
        // source_segment_id is the id of the Q&A block the card was generated from, as carried in
        // the note's Markdown (```quill-qa:<id>). It's what makes re-syncing a note an update
        // rather than a duplicate-generator: the card's SM-2 columns survive edits to its text.
        db.execSQL("CREATE TABLE flashcards (" +
                "id TEXT PRIMARY KEY, " +
                "note_id TEXT, " +
                "source_segment_id TEXT, " +
                "front TEXT, " +
                "back TEXT, " +
                "interval INTEGER DEFAULT 1, " +
                "repetitions INTEGER DEFAULT 0, " +
                "easiness REAL DEFAULT 2.5, " +
                "next_review INTEGER, " +
                "last_reviewed_at INTEGER, " +
                // Stamped when the note stops being able to produce this card — the Q&A block was
                // deleted, or one of its halves was emptied. The row stays, so refilling the half
                // brings the card back with its schedule intact; every count of "what is there to
                // review" leaves stamped rows out. See FlashcardRepository.markOrphansSync.
                "orphaned_at INTEGER, " +
                "FOREIGN KEY(note_id) REFERENCES notes(id))");

        // A quiz is a *marker*, not a question set: it records that a note was turned into one.
        // The questions themselves are generated fresh from the note's Q&A blocks at the start of
        // every attempt, so they can't go stale against an edited note and the options land in a
        // different order each time.
        db.execSQL("CREATE TABLE quizzes (" +
                "id TEXT PRIMARY KEY, " +
                "note_id TEXT NOT NULL, " +
                "created_at INTEGER, " +
                "FOREIGN KEY(note_id) REFERENCES notes(id))");

        // total is stored per attempt rather than read off the quiz: a note gains and loses Q&A
        // blocks over time, so "7 / 9" is only meaningful next to the 9 that was true that day.
        // answered is what separates an abandoned attempt from a bad one — 2/12 having answered
        // three questions and 2/12 having answered all twelve are not the same afternoon.
        db.execSQL("CREATE TABLE quiz_attempts (" +
                "id TEXT PRIMARY KEY, " +
                "quiz_id TEXT NOT NULL, " +
                "score INTEGER DEFAULT 0, " +
                "answered INTEGER DEFAULT 0, " +
                "total INTEGER DEFAULT 0, " +
                "status TEXT, " +
                "started_at INTEGER, " +
                "finished_at INTEGER, " +
                "FOREIGN KEY(quiz_id) REFERENCES quizzes(id))");

        db.execSQL("CREATE TABLE voice_memos (" +
                "id TEXT PRIMARY KEY, " +
                "note_id TEXT, " +
                "file_path TEXT, " +
                "transcript TEXT, " +
                "duration_ms INTEGER, " +
                "created_at INTEGER, " +
                "FOREIGN KEY(note_id) REFERENCES notes(id))");

        db.execSQL("CREATE TABLE outbox (" +
                "id TEXT PRIMARY KEY, " +
                "type TEXT, " +
                "payload_blob BLOB, " +
                "target_device_id TEXT, " +
                "created_at INTEGER)");

        // Media asset registry. A note's text lives in notes.content_blob as one Markdown
        // document; this table holds only what a Markdown link has nowhere to put — where the
        // file is, how wide to draw it, how long it runs, its transcript. Rows are referenced by
        // id from the document ("quill://image/<id>"), so there's no position column: ordering is
        // the document's job, and a row is reachable only while some document still names it.
        db.execSQL("CREATE TABLE note_segments (" +
                "id TEXT PRIMARY KEY, " +
                "note_id TEXT NOT NULL, " +
                "type INTEGER NOT NULL, " +
                "file_path TEXT, " +
                "transcript TEXT, " +
                "duration_ms INTEGER, " +
                "width INTEGER, " +
                "created_at INTEGER, " +
                "FOREIGN KEY(note_id) REFERENCES notes(id))");

        // Which notes embed which whiteboards. Not the source of truth — the embed lives in the
        // note's Markdown, and this is rewritten from it on every save, the way note_segments is.
        // It exists because a locked note's Markdown is ciphertext: without a link outside the
        // encrypted body, there is no way to ask "is this board inside a collection that is shut",
        // and an imported board would sit on Home with its drawing showing. Many-to-many on
        // purpose — an embed can be imported into a second note without moving.
        db.execSQL("CREATE TABLE note_whiteboards (" +
                "note_id TEXT NOT NULL, " +
                "whiteboard_id TEXT NOT NULL, " +
                "PRIMARY KEY(note_id, whiteboard_id), " +
                "FOREIGN KEY(note_id) REFERENCES notes(id), " +
                "FOREIGN KEY(whiteboard_id) REFERENCES whiteboards(id))");

        db.execSQL("CREATE TABLE tags (" +
                "id TEXT PRIMARY KEY, " +
                "name TEXT, " +
                "color INTEGER, " +
                "created_at INTEGER)");

        db.execSQL("CREATE TABLE note_tags (" +
                "note_id TEXT NOT NULL, " +
                "tag_id TEXT NOT NULL, " +
                "PRIMARY KEY(note_id, tag_id), " +
                "FOREIGN KEY(note_id) REFERENCES notes(id), " +
                "FOREIGN KEY(tag_id) REFERENCES tags(id))");

        db.execSQL("CREATE TABLE IF NOT EXISTS quiz_attempt_answers (" +
                "id TEXT PRIMARY KEY, " +
                "attempt_id TEXT NOT NULL, " +
                "position INTEGER NOT NULL, " +
                "source_id TEXT, " +
                "prompt TEXT, " +
                // The options as they were shown, in the order they were shown, as a JSON array.
                // Storing them is the whole point: the generator shuffles per attempt, so a paper
                // rebuilt from the note would put the same answers under different letters and
                // stop being the paper the user actually sat.
                "options TEXT, " +
                "correct_index INTEGER, " +
                // -1 for a question left blank, matching QuizSession.NO_SELECTION.
                "selected_index INTEGER, " +
                "FOREIGN KEY(attempt_id) REFERENCES quiz_attempts(id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_quiz_attempt_answers_attempt "
                + "ON quiz_attempt_answers(attempt_id)");

        // ---------- FTS5 virtual table ----------

        // Standalone (not content='notes') because the indexed body is the Markdown document
        // flattened to plain text, which no column on `notes` holds — NoteRepository writes both
        // columns in the same transaction as the save.
        ensureNotesFts(db);

        // ---------- Indexes (recommended for FK lookups) ----------

        db.execSQL("CREATE INDEX idx_notes_collection_id ON notes(collection_id)");
        db.execSQL("CREATE INDEX idx_whiteboards_note_id ON whiteboards(note_id)");
        db.execSQL("CREATE INDEX idx_strokes_whiteboard_id ON strokes(whiteboard_id)");
        db.execSQL("CREATE INDEX idx_flashcards_note_id ON flashcards(note_id)");
        db.execSQL("CREATE INDEX idx_flashcards_source_segment_id ON flashcards(source_segment_id)");
        // A note has at most one quiz — "Make quiz" on a note that already has one opens it rather
        // than making a second, and the constraint is what guarantees that rather than a convention.
        db.execSQL("CREATE UNIQUE INDEX idx_quizzes_note_id ON quizzes(note_id)");
        db.execSQL("CREATE INDEX idx_quiz_attempts_quiz_id ON quiz_attempts(quiz_id)");
        db.execSQL("CREATE INDEX idx_voice_memos_note_id ON voice_memos(note_id)");
        db.execSQL("CREATE INDEX idx_note_segments_note_id ON note_segments(note_id)");
        db.execSQL("CREATE INDEX idx_note_tags_tag_id ON note_tags(tag_id)");
        // Indexed by board, not by note: the question this table answers is "which notes hold this
        // board", asked once per board on every Home load.
        db.execSQL("CREATE INDEX idx_note_whiteboards_whiteboard_id " +
                "ON note_whiteboards(whiteboard_id)");

    }

    /**
     * Creates the standalone FTS5 index if this SQLite build has FTS5, and fills it from whatever
     * is already stored.
     *
     * <p>The create is schema; the fill is a migration, which is why the second half lives in
     * {@link Migrations}. On a database being created from nothing it finds no rows and does
     * nothing, which is the same no-op by a different route.
     */
    static void ensureNotesFts(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts "
                    + "USING fts5(note_id UNINDEXED, title, body)");
        } catch (SQLException e) {
            Log.w("AppDatabase", "fts5 unavailable, skipping notes_fts table", e);
            return;
        }
        Migrations.backfillNotesFts(db);
    }

    /**
     * Indexes what is already there, once.
     *
     * <p>Only when the index is empty: a populated one is kept current by every save, and
     * re-reading every note on each launch to confirm that would be a lot of work to learn nothing.
     * An app with no notes yet also finds nothing to do, which is the same no-op by a different
     * route.
     *
     * <p>Locked collections are skipped rather than decrypted. The index stores the body as plain
     * text, so filing one here would put a readable copy of the encrypted note in a table with no
     * lock on it — the same reason {@code NoteRepository} removes a note from the index when its
     * collection is locked. Those notes join the index if and when the collection is unlocked and
     * they are next saved.
     */
}
