package mse.quill.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.List;
import java.util.Set;

import mse.quill.data.serialization.NoteDocument;

/**
 * The {@code note_whiteboards} table: which notes embed which boards.
 *
 * <p><b>An index, not a source of truth.</b> The embed itself is a line in the note's Markdown, and
 * these rows are rewritten from it — on every save, and on both directions of the collection lock
 * migration. Nothing reads them to decide what a note contains.
 *
 * <p>They exist for one question the Markdown cannot answer: <em>is this whiteboard inside a
 * collection that is shut?</em> A locked note's body is ciphertext, so the reference is unreadable
 * exactly when it matters most. {@code whiteboards.note_id} doesn't answer it either — that column
 * records the note a board was <em>created from</em>, and "Import whiteboard" attaches an existing
 * board to a note without touching it, which is how an imported board kept showing up on Home with
 * its drawing visible while the note holding it was locked.
 *
 * <p>Many-to-many, because embedding is: the same board can be imported into a second note without
 * leaving the first, so a board is hidden if <em>any</em> note holding it is hidden.
 *
 * <p>Every method runs on the disk thread.
 */
final class WhiteboardLinks {

    private WhiteboardLinks() {}

    /** Rewrites one note's links to match the document being stored for it. */
    static void replace(SQLiteDatabase db, String noteId, String markdown) {
        db.delete("note_whiteboards", "note_id = ?", new String[]{noteId});

        List<String> boardIds = NoteDocument.whiteboardIdsIn(markdown);
        for (String boardId : boardIds) {
            ContentValues cv = new ContentValues();
            cv.put("note_id", noteId);
            cv.put("whiteboard_id", boardId);
            // The same board twice in one note is a legal document — one row is what the table says
            // about it either way.
            db.insertWithOnConflict("note_whiteboards", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    /**
     * SQL excluding boards embedded in a note belonging to one of {@code hidden}, for a query over
     * {@code whiteboards w}. Empty when nothing is hidden. Takes one argument per hidden id.
     */
    static String hiddenClause(Set<String> hidden) {
        if (hidden.isEmpty()) return "";
        return " AND NOT EXISTS (SELECT 1 FROM note_whiteboards nw " +
                "JOIN notes ln ON ln.id = nw.note_id " +
                "WHERE nw.whiteboard_id = w.id AND ln.collection_id IN (" +
                placeholders(hidden.size()) + ")) ";
    }

    /** The same test for a single board, for the callers that hold an id rather than a query. */
    static boolean isHidden(SQLiteDatabase db, String whiteboardId, Set<String> hidden) {
        if (whiteboardId == null || hidden.isEmpty()) return false;

        String[] args = new String[hidden.size() + 1];
        args[0] = whiteboardId;
        int i = 1;
        for (String id : hidden) args[i++] = id;

        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM note_whiteboards nw JOIN notes ln ON ln.id = nw.note_id " +
                        "WHERE nw.whiteboard_id = ? AND ln.collection_id IN (" +
                        placeholders(hidden.size()) + ") LIMIT 1",
                args)) {
            return c.moveToFirst();
        }
    }

    private static String placeholders(int count) {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sql.append(',');
            sql.append('?');
        }
        return sql.toString();
    }
}
