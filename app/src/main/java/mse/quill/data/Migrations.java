package mse.quill.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import mse.quill.data.model.AudioSegment;
import mse.quill.data.model.ImageSegment;
import mse.quill.data.model.NoteSegment;
import mse.quill.data.model.TextSegment;
import mse.quill.data.serialization.NoteDocument;
import mse.quill.study.quiz.QuizSession;

/**
 * Everything that has to cope with a Quill database that already exists.
 *
 * <p>Split out of {@link AppDatabase} on 2026-08-28, where it was ~370 of that file's 748 lines.
 * This half only grows, and it is the half nobody should edit casually: it runs against databases
 * that have been through every shape Quill has ever had, on devices nobody can inspect.
 *
 * <p><b>Never destructive, on any path.</b> {@link AppDatabase#onUpgrade} used to drop and recreate
 * everything below v3. That was accepted as dev-stage policy and duly wiped a real notebook on
 * 2026-07-28. There is no rebuild branch left here for a later edit to fall back into, and there
 * must not be one.
 *
 * <p>{@link #ensureAdditiveSchema} checks for <em>columns</em> rather than trusting version
 * numbers. That looks redundant and is not: two branches independently shipped a "version 4"
 * meaning different things, so the number alone cannot say what a given database actually holds.
 */
final class Migrations {

    private static final String TAG = "AppDatabase";

    private Migrations() {}

    static void migrateLegacyNotesToMarkdown(SQLiteDatabase db) {
        if (!tableExists(db, "note_segments")) return;
        // The one reliable marker of the old shape. A database that has already been converted —
        // or was created fresh at v3+ and is only here because of a stale version number — has no
        // position column, and this whole method is then a no-op rather than a second conversion.
        if (!columnExists(db, "note_segments", "position")) return;

        Log.i("AppDatabase", "converting pre-v3 note segments to Markdown documents");

        List<String> noteIds = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id FROM notes", null)) {
            while (c.moveToNext()) noteIds.add(c.getString(0));
        }

        for (String noteId : noteIds) {
            try {
                String markdown = legacyNoteToMarkdown(db, noteId);
                ContentValues cv = new ContentValues();
                cv.put("content_blob", markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                db.update("notes", cv, "id = ?", new String[]{noteId});
            } catch (RuntimeException e) {
                // One unreadable note must not cost the user the other ninety. Its rows stay where
                // they are; the reshape below keeps its media, and the body is what is lost.
                Log.w("AppDatabase", "could not convert note " + noteId + ", leaving it empty", e);
            }
        }

        reshapeLegacySegmentTable(db);
    }

    /** Reads one note's old segment rows, in their stored order, and composes the document. */
    private static String legacyNoteToMarkdown(SQLiteDatabase db, String noteId) {
        List<NoteSegment> segments = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT id, type, text_content, file_path, duration_ms, width FROM note_segments "
                        + "WHERE note_id = ? ORDER BY position", new String[]{noteId})) {
            while (c.moveToNext()) {
                String id = c.getString(0);
                int type = c.getInt(1);
                if (type == NoteSegment.TYPE_TEXT) {
                    segments.add(new TextSegment(legacyHtmlToSpannable(c.isNull(2) ? null : c.getBlob(2))));
                } else if (type == NoteSegment.TYPE_IMAGE) {
                    ImageSegment image = new ImageSegment(c.isNull(3) ? null : c.getString(3));
                    image.id = id;
                    image.displayWidth = c.isNull(5) ? 0 : c.getInt(5);
                    segments.add(image);
                } else if (type == NoteSegment.TYPE_AUDIO) {
                    AudioSegment audio = new AudioSegment(
                            c.isNull(3) ? null : c.getString(3), c.isNull(4) ? 0 : c.getInt(4));
                    audio.id = id;
                    segments.add(audio);
                }
                // Anything else is a type this schema never wrote; skipping it is correct.
            }
        }
        return NoteDocument.toMarkdown(segments);
    }

    /**
     * Decodes a pre-v3 {@code text_content} blob.
     *
     * <p>This is the body of the deleted {@code SpanSerializer.fromBytes}, kept here rather than
     * resurrected as a class: it decodes a format nothing writes any more, so it belongs with the
     * migration that is the last thing on earth to read it.
     *
     * <p>The newline collapsing is not cosmetic. {@code Html.toHtml} wrote each line as its own
     * {@code <p>}, and {@code fromHtml} rejoins paragraphs with a blank line — so without this
     * every note would come through the migration with its line spacing doubled.
     */
    private static android.text.Spannable legacyHtmlToSpannable(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return new android.text.SpannableStringBuilder("");

        String html = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        android.text.Spanned parsed = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY);
        android.text.SpannableStringBuilder builder = new android.text.SpannableStringBuilder(parsed);

        for (int i = builder.length() - 2; i >= 0; i--) {
            if (builder.charAt(i) == '\n' && builder.charAt(i + 1) == '\n') {
                builder.delete(i, i + 1);
            }
        }
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) == '\n') {
            builder.delete(builder.length() - 1, builder.length());
        }
        return builder;
    }

    /**
     * Rebuilds {@code note_segments} in its v3 shape, keeping the media rows and discarding the
     * text rows whose content is now in the documents.
     *
     * <p>Copy-drop-rename rather than {@code ALTER TABLE ... DROP COLUMN}, which SQLite only
     * learned in 3.35 (Android 14). This app supports API 26.
     */
    private static void reshapeLegacySegmentTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE note_segments_v3 (" +
                "id TEXT PRIMARY KEY, " +
                "note_id TEXT NOT NULL, " +
                "type INTEGER NOT NULL, " +
                "file_path TEXT, " +
                "transcript TEXT, " +
                "duration_ms INTEGER, " +
                "width INTEGER, " +
                "created_at INTEGER, " +
                "FOREIGN KEY(note_id) REFERENCES notes(id))");
        db.execSQL("INSERT INTO note_segments_v3 "
                + "(id, note_id, type, file_path, transcript, duration_ms, width, created_at) "
                + "SELECT id, note_id, type, file_path, transcript, duration_ms, width, created_at "
                + "FROM note_segments WHERE type IN ("
                + NoteSegment.TYPE_IMAGE + ", " + NoteSegment.TYPE_AUDIO + ")");
        db.execSQL("DROP TABLE note_segments");
        db.execSQL("ALTER TABLE note_segments_v3 RENAME TO note_segments");
        // Dropping the table took its index with it.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_note_segments_note_id ON note_segments(note_id)");
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
    static void ensureAdditiveSchema(SQLiteDatabase db) {
        // Per-collection encryption. Shipped in onCreate but, for a long time, never added here, so
        // a database made before it upgraded into a build that queries a column it doesn't have —
        // every read that consults the lock throwing "no such column" on exactly the devices with
        // notes worth locking. Default 0: an existing collection is unlocked, which is what it was.
        //
        // First, not in its old place further down, because backfillNotesFts joins on this column.
        // Adding it later meant a database old enough to lack it — every pre-v3 one — silently
        // skipped the search backfill and came out of the upgrade with an empty index.
        addColumnIfMissing(db, "collections", "biometric_locked", "INTEGER DEFAULT 0");

        // Links a flashcard back to the Q&A block it came from, and records when it was last seen.
        addColumnIfMissing(db, "flashcards", "source_segment_id", "TEXT");
        addColumnIfMissing(db, "flashcards", "last_reviewed_at", "INTEGER");
        // Left null on existing rows, which is the right default: nothing is orphaned until the
        // next sync of its note looks at the blocks and decides otherwise.
        addColumnIfMissing(db, "flashcards", "orphaned_at", "INTEGER");

        // The per-question record of an attempt: what was asked, in what order the options were
        // shown, and what was picked. New table, so nothing existing is touched.
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

        // The search index was only ever created in onCreate, so every database that upgraded from
        // v3 has been running without one — silently, because both the writes and the reads are
        // guarded for FTS5-less builds and an absent table looks exactly like an absent module.
        // Creating it here is most of the fix; backfilling is the rest, or the index would only
        // know about notes saved after today.
        Schema.ensureNotesFts(db);
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
     * Creates {@code notes_fts} if it isn't there, and fills it from the notes table if it is
     * empty.
     *
     * <p>Standalone (not {@code content='notes'}) because the indexed body is the Markdown document
     * flattened to plain text, which no column on {@code notes} holds — {@code NoteRepository}
     * writes both columns in the same transaction as the save.
     *
     * <p>Some SQLite builds — notably some emulator system images — aren't compiled with FTS5. The
     * search falls back to matching titles in memory, so a missing module skips the table rather
     * than failing database creation.
     */

    static void backfillNotesFts(SQLiteDatabase db) {
        try (android.database.Cursor count =
                     db.rawQuery("SELECT COUNT(*) FROM notes_fts", null)) {
            if (count.moveToFirst() && count.getInt(0) > 0) return;
        } catch (SQLException e) {
            return;
        }

        try (android.database.Cursor c = db.rawQuery(
                "SELECT n.id, n.title, n.content_blob FROM notes n "
                        + "LEFT JOIN collections c ON c.id = n.collection_id "
                        + "WHERE n.deleted_at IS NULL "
                        + "AND (c.biometric_locked IS NULL OR c.biometric_locked = 0)", null)) {
            while (c.moveToNext()) {
                String markdown = c.isNull(2)
                        ? "" : new String(c.getBlob(2), java.nio.charset.StandardCharsets.UTF_8);
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put("note_id", c.getString(0));
                cv.put("title", c.isNull(1) ? "" : c.getString(1));
                cv.put("body", mse.quill.data.serialization.NoteDocument.toPlainText(markdown));
                db.insert("notes_fts", null, cv);
            }
        } catch (SQLException e) {
            Log.w("AppDatabase", "could not backfill notes_fts", e);
        }
    }

    /**
     * Adds a column unless the table already has it. SQLite has no
     * {@code ALTER TABLE … ADD COLUMN IF NOT EXISTS}, and re-adding one throws.
     *
     * @return true if the column was added, false if it was already there.
     */
    private static boolean addColumnIfMissing(SQLiteDatabase db, String table, String column, String type) {
        if (columnExists(db, table, column)) return false;
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        return true;
    }

    private static boolean columnExists(SQLiteDatabase db, String table, String column) {
        try (android.database.Cursor cursor =
                     db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIndex = cursor.getColumnIndex("name");
            while (cursor.moveToNext()) {
                if (column.equals(cursor.getString(nameIndex))) return true;
            }
        }
        return false;
    }

    private static boolean tableExists(SQLiteDatabase db, String table) {
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
    private static void backfillWhiteboardLinks(SQLiteDatabase db) {
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
}
