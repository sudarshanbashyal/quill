package mse.quill.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import mse.quill.data.serialization.NoteDocument;

public class AppDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "quill.db";
    /**
     * 6, not 5: both the flashcards branch and the whiteboard branch shipped a "version 4" meaning
     * different things, so the next number has to clear the highest either of them used. See
     * {@link #ensureAdditiveSchema} for why the migration doesn't trust this number alone.
     */
    private static final int DATABASE_VERSION = 9;
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

    /**
     * Closes the shared connection and deletes the database file. Only {@link mse.quill.util.DataWipe}
     * has any business calling this.
     *
     * <p>The close and the null have to happen before the delete, and together: deleting the file
     * out from under an open {@code SQLiteOpenHelper} leaves the helper holding a handle to
     * something that is no longer there, and leaving the singleton in place would hand that same
     * dead helper to the next caller. Nulling it means the next {@link #getInstance} builds a fresh
     * one, which recreates the schema through {@link #onCreate} — an empty Quill rather than a
     * broken one.
     */
    public static synchronized void destroy(Context context) {
        if (instance != null) {
            instance.close();
            instance = null;
        }
        context.getApplicationContext().deleteDatabase(DATABASE_NAME);
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
        // Indexed by board, not by note: the question this table answers is "which notes hold this
        // board", asked once per board on every Home load.
        db.execSQL("CREATE INDEX idx_note_whiteboards_whiteboard_id " +
                "ON note_whiteboards(whiteboard_id)");

    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v3 is the schema the Markdown migration shipped, so from there on upgrades are additive:
        // dropping a user's notes to add columns to a table they've never filled would be a poor
        // trade. Anything older is a development-era schema and still gets rebuilt.
        if (oldVersion >= 3) {
            ensureAdditiveSchema(db);
            return;
        }

        rebuild(db);
    }

    /**
     * Brings any v3-or-later database up to the current schema, by asking what it already has
     * rather than by trusting its version number.
     *
     * <p>That indirection is not decoration. Two branches independently shipped a "version 4":
     * on the flashcards line it meant {@code flashcards.source_segment_id}, on the whiteboard line
     * it meant {@code whiteboards.title}. A device sitting at v4 is therefore ambiguous — a
     * numbered ladder would run the wrong step for one of the two lineages and leave the other's
     * columns missing, which surfaces later as a query against a column that isn't there. Checking
     * for each column makes every step idempotent and the version number merely a trigger.
     */
    private void ensureAdditiveSchema(SQLiteDatabase db) {
        // Links a flashcard back to the Q&A block it came from, and records when it was last seen.
        addColumnIfMissing(db, "flashcards", "source_segment_id", "TEXT");
        addColumnIfMissing(db, "flashcards", "last_reviewed_at", "INTEGER");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_flashcards_source_segment_id " +
                "ON flashcards(source_segment_id)");

        // Quizzes and their attempt history. New tables, so nothing existing is touched.
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

        // Typed text on a board. A new table, so nothing existing is touched.
        db.execSQL("CREATE TABLE IF NOT EXISTS whiteboard_texts (" +
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
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_whiteboard_texts_whiteboard_id " +
                "ON whiteboard_texts(whiteboard_id)");

        // Per-collection encryption. Shipped in onCreate but never added here, so a database made
        // before it upgraded into a build that queries a column it doesn't have — every read that
        // consults the lock throwing "no such column" on exactly the devices that have notes worth
        // locking. Default 0: an existing collection is unlocked, which is what it was.
        addColumnIfMissing(db, "collections", "biometric_locked", "INTEGER DEFAULT 0");

        // Which notes embed which whiteboards; see onCreate for why it exists. Backfilled once,
        // when the table is first created, because until then the only record of an embed is the
        // note's Markdown.
        boolean linksAreNew = !tableExists(db, "note_whiteboards");
        db.execSQL("CREATE TABLE IF NOT EXISTS note_whiteboards (" +
                "note_id TEXT NOT NULL, " +
                "whiteboard_id TEXT NOT NULL, " +
                "PRIMARY KEY(note_id, whiteboard_id), " +
                "FOREIGN KEY(note_id) REFERENCES notes(id), " +
                "FOREIGN KEY(whiteboard_id) REFERENCES whiteboards(id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_note_whiteboards_whiteboard_id " +
                "ON note_whiteboards(whiteboard_id)");
        if (linksAreNew) backfillWhiteboardLinks(db);

        // Whiteboards gained a name and timestamps so Home can list them: a board has no body text
        // to derive a preview or a date from the way a note does.
        boolean whiteboardsDated = !addColumnIfMissing(db, "whiteboards", "title", "TEXT");
        addColumnIfMissing(db, "whiteboards", "created_at", "INTEGER");
        addColumnIfMissing(db, "whiteboards", "updated_at", "INTEGER");
        // Paper style. Defaults to 0 (plain white), which is what every existing board was.
        addColumnIfMissing(db, "whiteboards", "background", "INTEGER DEFAULT 0");
        if (!whiteboardsDated) {
            // Rows that predate the columns have no timestamps; date them from their strokes so
            // they don't all sort to the top of Home as "just now". Guarded on NULL so a re-run
            // can't overwrite a real timestamp.
            db.execSQL("UPDATE whiteboards SET created_at = COALESCE(" +
                    "(SELECT MIN(s.created_at) FROM strokes s WHERE s.whiteboard_id = whiteboards.id), " +
                    "CAST(strftime('%s','now') AS INTEGER) * 1000), " +
                    "updated_at = COALESCE(" +
                    "(SELECT MAX(s.created_at) FROM strokes s WHERE s.whiteboard_id = whiteboards.id), " +
                    "CAST(strftime('%s','now') AS INTEGER) * 1000) " +
                    "WHERE created_at IS NULL OR updated_at IS NULL");
        }
    }

    /**
     * Adds a column unless the table already has it. SQLite has no
     * {@code ALTER TABLE … ADD COLUMN IF NOT EXISTS}, and re-adding one throws.
     *
     * @return true if the column was added, false if it was already there.
     */
    private boolean addColumnIfMissing(SQLiteDatabase db, String table, String column, String type) {
        try (android.database.Cursor cursor =
                     db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIndex = cursor.getColumnIndex("name");
            while (cursor.moveToNext()) {
                if (column.equals(cursor.getString(nameIndex))) return false;
            }
        }
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        return true;
    }

    private boolean tableExists(SQLiteDatabase db, String table) {
        try (android.database.Cursor c = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                new String[]{table})) {
            return c.moveToFirst();
        }
    }

    /**
     * Reads every note that can be read and records the whiteboards its Markdown embeds.
     *
     * <p>Notes in a locked collection are skipped, not failed: their bodies are ciphertext and the
     * key is behind a prompt no migration can raise. Those get their links when the collection is
     * next locked or unlocked — {@code CollectionLockRepository} holds the plaintext at both — or
     * on the next save, whichever comes first.
     */
    private void backfillWhiteboardLinks(SQLiteDatabase db) {
        List<String> lockedIds = new ArrayList<>();
        try (android.database.Cursor c = db.rawQuery(
                "SELECT id FROM collections WHERE biometric_locked = 1", null)) {
            while (c.moveToNext()) lockedIds.add(c.getString(0));
        }

        try (android.database.Cursor c = db.rawQuery(
                "SELECT id, collection_id, content_blob FROM notes", null)) {
            while (c.moveToNext()) {
                if (!c.isNull(1) && lockedIds.contains(c.getString(1))) continue;
                if (c.isNull(2)) continue;

                String markdown = new String(c.getBlob(2), java.nio.charset.StandardCharsets.UTF_8);
                for (String boardId : NoteDocument.whiteboardIdsIn(markdown)) {
                    ContentValues cv = new ContentValues();
                    cv.put("note_id", c.getString(0));
                    cv.put("whiteboard_id", boardId);
                    db.insertWithOnConflict("note_whiteboards", null, cv,
                            SQLiteDatabase.CONFLICT_IGNORE);
                }
            }
        }
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
