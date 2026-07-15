package mse.quill.model;

/**
 * Whiteboard
 *
 * Represents a single whiteboard session tied to a note.
 * Maps directly to the `whiteboards` table.
 *
 *   id      TEXT PRIMARY KEY
 *   noteId  TEXT   → whiteboards.note_id (parent note this whiteboard belongs to)
 */
public class Whiteboard {
    public String id;
    public String noteId;
}