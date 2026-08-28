package mse.quill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;

import mse.quill.data.AppDatabase;

/**
 * The upgrade path, exercised against a database shaped the way v2 actually shaped one.
 *
 * <p>This is the test the destructive {@code onUpgrade} never had. It seeds a pre-Markdown
 * database with a note, a picture, a board, a card and a tag, opens it through the current helper,
 * and asserts that all of it is still there afterwards — and, just as importantly, that the
 * converted database can still be <em>written to</em>, which is the failure the old
 * {@code note_segments} shape would have caused silently.
 *
 * <p>The v2 schema is spelled out below rather than imported. That is deliberate: this test is
 * pinning down what shipped, and it has to keep saying so even after the production {@code onCreate}
 * has moved on again.
 */
@RunWith(AndroidJUnit4.class)
public class DatabaseMigrationTest {

    private static final String TEST_DB = "quill_migration_test.db";

    /** The ids are fixed so the assertions can name them. */
    private static final String NOTE_ID = "note-1";
    private static final String IMAGE_SEGMENT_ID = "seg-image-1";
    private static final String COLLECTION_ID = "collection-1";
    private static final String WHITEBOARD_ID = "board-1";

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(TEST_DB);
    }

    @After
    public void tearDown() {
        context.deleteDatabase(TEST_DB);
    }

    @Test
    public void upgradeFromV2KeepsEveryRowAndComposesTheDocument() {
        String bodyHtml = seedLegacyDatabase();

        AppDatabase helper = AppDatabase.openForTest(context, TEST_DB);
        SQLiteDatabase db = helper.getWritableDatabase();

        // ── The note survived, and its body became one Markdown document ──────────────────

        String markdown = readNoteMarkdown(db, NOTE_ID);
        assertNotNull("the note's body was dropped by the upgrade", markdown);
        assertTrue("first text block missing from the document: " + markdown,
                markdown.contains("Photosynthesis"));
        assertTrue("second text block missing from the document: " + markdown,
                markdown.contains("in the chloroplast"));
        assertTrue("bold formatting was lost converting " + bodyHtml + " -> " + markdown,
                markdown.contains("**light**"));
        assertTrue("the image is no longer referenced by the document: " + markdown,
                markdown.contains("quill://image/" + IMAGE_SEGMENT_ID));

        // Order is the whole point of the position column the new schema no longer has.
        assertTrue("blocks came out of order: " + markdown,
                markdown.indexOf("Photosynthesis") < markdown.indexOf("quill://image/")
                        && markdown.indexOf("quill://image/") < markdown.indexOf("in the chloroplast"));

        // Doubled line spacing is what happens if the paragraph collapse is skipped.
        assertFalse("line spacing was doubled by the HTML round trip: " + markdown,
                markdown.contains("\n\n\n"));

        // ── note_segments kept the media and shed the text ───────────────────────────────

        assertEquals("text rows should not survive as registry rows",
                0, count(db, "SELECT COUNT(*) FROM note_segments WHERE type = 0"));
        assertEquals("the picture's registry row was lost",
                1, count(db, "SELECT COUNT(*) FROM note_segments WHERE id = '" + IMAGE_SEGMENT_ID + "'"));
        assertEquals("the picture's file path was lost", "/data/pic.jpg",
                readString(db, "SELECT file_path FROM note_segments WHERE id = ?", IMAGE_SEGMENT_ID));
        assertFalse("the legacy position column is still on note_segments, so inserts will fail",
                columnExists(db, "note_segments", "position"));

        // ── Everything else came through untouched ───────────────────────────────────────

        assertEquals("collections were dropped", 1, count(db, "SELECT COUNT(*) FROM collections"));
        assertEquals("Biology",
                readString(db, "SELECT name FROM collections WHERE id = ?", COLLECTION_ID));
        assertEquals("the note lost its collection", COLLECTION_ID,
                readString(db, "SELECT collection_id FROM notes WHERE id = ?", NOTE_ID));
        assertEquals("whiteboards were dropped", 1, count(db, "SELECT COUNT(*) FROM whiteboards"));
        assertEquals("strokes were dropped", 1, count(db, "SELECT COUNT(*) FROM strokes"));
        assertEquals("tags were dropped", 1, count(db, "SELECT COUNT(*) FROM tags"));
        assertEquals("the note lost its tag", 1, count(db, "SELECT COUNT(*) FROM note_tags"));
        assertEquals("flashcards were dropped", 1, count(db, "SELECT COUNT(*) FROM flashcards"));
        assertEquals("a card's review schedule was reset by the upgrade",
                6, count(db, "SELECT interval FROM flashcards WHERE id = 'card-1'"));

        // ── Columns added after v2 are present, with the defaults an old row should get ──

        assertTrue(columnExists(db, "collections", "biometric_locked"));
        assertEquals("an existing collection should come out unlocked",
                0, count(db, "SELECT biometric_locked FROM collections WHERE id = '" + COLLECTION_ID + "'"));
        assertTrue(columnExists(db, "whiteboards", "title"));
        assertTrue(columnExists(db, "whiteboards", "background"));
        assertTrue(columnExists(db, "flashcards", "source_segment_id"));
        assertTrue(columnExists(db, "flashcards", "orphaned_at"));
        assertTrue(tableExists(db, "quizzes"));
        assertTrue(tableExists(db, "quiz_attempts"));
        assertTrue(tableExists(db, "quiz_attempt_answers"));
        assertTrue(tableExists(db, "whiteboard_texts"));
        assertTrue(tableExists(db, "note_whiteboards"));

        // A board that predates the timestamp columns should be dated from its strokes rather
        // than sorting to the top of Home as "just now".
        assertEquals("board was not dated from its strokes",
                1_500_000_000_000L,
                readLong(db, "SELECT updated_at FROM whiteboards WHERE id = ?", WHITEBOARD_ID));

        // ── And the converted database is still writable ─────────────────────────────────

        ContentValues asset = new ContentValues();
        asset.put("id", "seg-image-2");
        asset.put("note_id", NOTE_ID);
        asset.put("type", 1);
        asset.put("file_path", "/data/pic2.jpg");
        assertTrue("inserting into the migrated note_segments failed",
                db.insert("note_segments", null, asset) != -1);

        helper.close();
    }

    /**
     * A second open must not convert anything a second time, or re-run the backfills.
     *
     * <p>Cheap to assert and worth asserting: an upgrade that is not idempotent only misbehaves on
     * the devices that upgrade twice, which is every device, eventually.
     */
    @Test
    public void upgradingATwiceUpgradedDatabaseChangesNothing() {
        seedLegacyDatabase();

        AppDatabase first = AppDatabase.openForTest(context, TEST_DB);
        String afterFirst = readNoteMarkdown(first.getWritableDatabase(), NOTE_ID);
        first.close();

        AppDatabase second = AppDatabase.openForTest(context, TEST_DB);
        SQLiteDatabase db = second.getWritableDatabase();
        assertEquals("the document was rewritten on a second open",
                afterFirst, readNoteMarkdown(db, NOTE_ID));
        assertEquals("the picture's registry row was duplicated",
                1, count(db, "SELECT COUNT(*) FROM note_segments"));
        second.close();
    }

    /** A downgrade must open rather than throw — see {@code AppDatabase.onDowngrade}. */
    @Test
    public void aDatabaseFromANewerBuildStillOpens() {
        seedLegacyDatabase();
        AppDatabase upgraded = AppDatabase.openForTest(context, TEST_DB);
        upgraded.getWritableDatabase().setVersion(9_999);
        upgraded.close();

        AppDatabase reopened = AppDatabase.openForTest(context, TEST_DB);
        assertNotNull("opening a database from a newer build threw instead of downgrading",
                readNoteMarkdown(reopened.getWritableDatabase(), NOTE_ID));
        reopened.close();
    }

    // ── Seeding ──────────────────────────────────────────────────────────────────────────

    /**
     * Builds a database exactly as v2 built one, and returns the HTML it stored for the note's
     * first text block so a failure can print what went in.
     */
    private String seedLegacyDatabase() {
        File path = context.getDatabasePath(TEST_DB);
        //noinspection ResultOfMethodCallIgnored
        path.getParentFile().mkdirs();
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(path, null);

        db.execSQL("CREATE TABLE collections (id TEXT PRIMARY KEY, name TEXT, color INTEGER, "
                + "created_at INTEGER)");
        db.execSQL("CREATE TABLE notes (id TEXT PRIMARY KEY, collection_id TEXT, title TEXT, "
                + "content_blob BLOB, created_at INTEGER, updated_at INTEGER, "
                + "author_device_id TEXT, vector_clock TEXT, deleted_at INTEGER, "
                + "location_lat REAL, location_lng REAL, location_name TEXT, pinned_at INTEGER, "
                + "FOREIGN KEY(collection_id) REFERENCES collections(id))");
        db.execSQL("CREATE TABLE whiteboards (id TEXT PRIMARY KEY, note_id TEXT, "
                + "FOREIGN KEY(note_id) REFERENCES notes(id))");
        db.execSQL("CREATE TABLE strokes (id TEXT PRIMARY KEY, whiteboard_id TEXT, author_id TEXT, "
                + "tool INTEGER, color INTEGER, width REAL, points_blob BLOB, created_at INTEGER, "
                + "FOREIGN KEY(whiteboard_id) REFERENCES whiteboards(id))");
        db.execSQL("CREATE TABLE flashcards (id TEXT PRIMARY KEY, note_id TEXT, front TEXT, "
                + "back TEXT, interval INTEGER DEFAULT 1, repetitions INTEGER DEFAULT 0, "
                + "easiness REAL DEFAULT 2.5, next_review INTEGER, "
                + "FOREIGN KEY(note_id) REFERENCES notes(id))");
        db.execSQL("CREATE TABLE voice_memos (id TEXT PRIMARY KEY, note_id TEXT, file_path TEXT, "
                + "transcript TEXT, duration_ms INTEGER, created_at INTEGER)");
        db.execSQL("CREATE TABLE outbox (id TEXT PRIMARY KEY, type TEXT, payload_blob BLOB, "
                + "target_device_id TEXT, created_at INTEGER)");
        // The shape that matters: position NOT NULL, and text stored in text_content.
        db.execSQL("CREATE TABLE note_segments (id TEXT PRIMARY KEY, note_id TEXT NOT NULL, "
                + "position INTEGER NOT NULL, type INTEGER NOT NULL, text_content BLOB, "
                + "file_path TEXT, transcript TEXT, duration_ms INTEGER, width INTEGER, "
                + "created_at INTEGER, FOREIGN KEY(note_id) REFERENCES notes(id))");
        db.execSQL("CREATE TABLE tags (id TEXT PRIMARY KEY, name TEXT, color INTEGER, "
                + "created_at INTEGER)");
        db.execSQL("CREATE TABLE note_tags (note_id TEXT NOT NULL, tag_id TEXT NOT NULL, "
                + "PRIMARY KEY(note_id, tag_id))");

        insert(db, "collections", "id", COLLECTION_ID, "name", "Biology", "created_at", 1L);
        insert(db, "notes", "id", NOTE_ID, "collection_id", COLLECTION_ID, "title", "Photosynthesis",
                "created_at", 1L, "updated_at", 2L);

        String firstHtml = legacyHtml(bold("Photosynthesis needs ", "light", " and water."));
        insertSegment(db, "seg-text-1", 0, 0, firstHtml.getBytes(StandardCharsets.UTF_8), null, 0);
        insertSegment(db, IMAGE_SEGMENT_ID, 1, 1, null, "/data/pic.jpg", 640);
        insertSegment(db, "seg-text-2", 2, 0,
                legacyHtml(new SpannableStringBuilder("It happens in the chloroplast."))
                        .getBytes(StandardCharsets.UTF_8), null, 0);

        insert(db, "whiteboards", "id", WHITEBOARD_ID, "note_id", NOTE_ID);
        insert(db, "strokes", "id", "stroke-1", "whiteboard_id", WHITEBOARD_ID,
                "tool", 0L, "color", 0L, "created_at", 1_500_000_000_000L);
        insert(db, "flashcards", "id", "card-1", "note_id", NOTE_ID, "front", "Q", "back", "A",
                "interval", 6L, "repetitions", 2L);
        insert(db, "tags", "id", "tag-1", "name", "exam", "created_at", 1L);
        insert(db, "note_tags", "note_id", NOTE_ID, "tag_id", "tag-1");

        db.setVersion(2);
        db.close();
        return firstHtml;
    }

    private static Spanned bold(String before, String boldPart, String after) {
        SpannableStringBuilder text = new SpannableStringBuilder(before);
        int start = text.length();
        text.append(boldPart);
        text.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append(after);
        return text;
    }

    /** What the since-deleted {@code SpanSerializer.toBytes} wrote. */
    private static String legacyHtml(Spanned spanned) {
        return Html.toHtml(spanned, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE);
    }

    private static void insertSegment(SQLiteDatabase db, String id, int position, int type,
                                      byte[] textContent, String filePath, int width) {
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("note_id", NOTE_ID);
        cv.put("position", position);
        cv.put("type", type);
        if (textContent != null) cv.put("text_content", textContent);
        if (filePath != null) cv.put("file_path", filePath);
        if (width > 0) cv.put("width", width);
        cv.put("created_at", 1L);
        db.insertOrThrow("note_segments", null, cv);
    }

    /** {@code insert(db, "notes", "id", "x", "title", "y")} — column, value, column, value. */
    private static void insert(SQLiteDatabase db, String table, Object... columnsAndValues) {
        ContentValues cv = new ContentValues();
        for (int i = 0; i < columnsAndValues.length; i += 2) {
            String column = (String) columnsAndValues[i];
            Object value = columnsAndValues[i + 1];
            if (value instanceof Long) cv.put(column, (Long) value);
            else cv.put(column, (String) value);
        }
        db.insertOrThrow(table, null, cv);
    }

    // ── Reading ──────────────────────────────────────────────────────────────────────────

    private static String readNoteMarkdown(SQLiteDatabase db, String noteId) {
        try (Cursor c = db.rawQuery("SELECT content_blob FROM notes WHERE id = ?",
                new String[]{noteId})) {
            if (!c.moveToFirst() || c.isNull(0)) return null;
            return new String(c.getBlob(0), StandardCharsets.UTF_8);
        }
    }

    private static long count(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getLong(0) : -1;
        }
    }

    private static String readString(SQLiteDatabase db, String sql, String arg) {
        try (Cursor c = db.rawQuery(sql, new String[]{arg})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private static long readLong(SQLiteDatabase db, String sql, String arg) {
        try (Cursor c = db.rawQuery(sql, new String[]{arg})) {
            return c.moveToFirst() ? c.getLong(0) : -1;
        }
    }

    private static boolean columnExists(SQLiteDatabase db, String table, String column) {
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int name = c.getColumnIndex("name");
            while (c.moveToNext()) {
                if (column.equals(c.getString(name))) return true;
            }
        }
        return false;
    }

    private static boolean tableExists(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                new String[]{table})) {
            return c.moveToFirst();
        }
    }
}
