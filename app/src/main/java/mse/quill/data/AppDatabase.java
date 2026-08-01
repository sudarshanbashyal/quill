package mse.quill.data;

import android.content.Context;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class AppDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "quill.db";
    private static final int DATABASE_VERSION = 5;
    private static volatile AppDatabase instance;

    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new AppDatabase(context.getApplicationContext());
        }
        return instance;
    }

    private AppDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {

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

        db.execSQL("CREATE TABLE whiteboards (" +
                "id TEXT PRIMARY KEY, " +
                "note_id TEXT, " +
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

        // ---------- FTS5 virtual table ----------

        // Standalone (not content='notes') because the indexed body is the Markdown document
        // flattened to plain text, which no column on `notes` holds — NoteRepository writes both
        // columns in the same transaction as the save.
        try {
            db.execSQL("CREATE VIRTUAL TABLE notes_fts USING fts5(note_id UNINDEXED, title, body)");
        } catch (SQLException e) {
            // Some SQLite builds (notably some emulator system images) aren't compiled with the
            // FTS5 module. The search UI still filters in memory, so skip the table rather than
            // failing database creation entirely — writes to it are guarded the same way.
            Log.w("AppDatabase", "fts5 unavailable, skipping notes_fts table", e);
        }

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

    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v3 is the schema the Markdown migration shipped, so from there on upgrades are additive:
        // dropping a user's notes to add two columns to a table they've never filled would be a
        // poor trade. Anything older is a development-era schema and still gets rebuilt.
        if (oldVersion >= 3) {
            if (oldVersion < 4) upgradeToV4(db);
            if (oldVersion < 5) upgradeToV5(db);
            return;
        }

        rebuild(db);
    }

    /** Links a flashcard back to the Q&A block it came from, and records when it was last seen. */
    private void upgradeToV4(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE flashcards ADD COLUMN source_segment_id TEXT");
        db.execSQL("ALTER TABLE flashcards ADD COLUMN last_reviewed_at INTEGER");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_flashcards_source_segment_id " +
                "ON flashcards(source_segment_id)");
    }

    /** Adds quizzes and their attempt history. Two new tables, so nothing existing is touched. */
    private void upgradeToV5(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS quizzes (" +
                "id TEXT PRIMARY KEY, " +
                "note_id TEXT NOT NULL, " +
                "created_at INTEGER, " +
                "FOREIGN KEY(note_id) REFERENCES notes(id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS quiz_attempts (" +
                "id TEXT PRIMARY KEY, " +
                "quiz_id TEXT NOT NULL, " +
                "score INTEGER DEFAULT 0, " +
                "answered INTEGER DEFAULT 0, " +
                "total INTEGER DEFAULT 0, " +
                "status TEXT, " +
                "started_at INTEGER, " +
                "finished_at INTEGER, " +
                "FOREIGN KEY(quiz_id) REFERENCES quizzes(id))");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_quizzes_note_id ON quizzes(note_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_quiz_attempts_quiz_id " +
                "ON quiz_attempts(quiz_id)");
    }

    private void rebuild(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS notes_fts");
        db.execSQL("DROP TABLE IF EXISTS note_tags");
        db.execSQL("DROP TABLE IF EXISTS tags");
        db.execSQL("DROP TABLE IF EXISTS note_segments");
        db.execSQL("DROP TABLE IF EXISTS outbox");
        db.execSQL("DROP TABLE IF EXISTS voice_memos");
        db.execSQL("DROP TABLE IF EXISTS quiz_attempts");
        db.execSQL("DROP TABLE IF EXISTS quizzes");
        db.execSQL("DROP TABLE IF EXISTS flashcards");
        db.execSQL("DROP TABLE IF EXISTS strokes");
        db.execSQL("DROP TABLE IF EXISTS whiteboards");
        db.execSQL("DROP TABLE IF EXISTS notes");
        db.execSQL("DROP TABLE IF EXISTS collections");
        onCreate(db);
    }
}
