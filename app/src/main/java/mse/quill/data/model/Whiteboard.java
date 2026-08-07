package mse.quill.data.model;

/**
 * Whiteboard
 *
 * Represents a single whiteboard session. Maps directly to the `whiteboards` table.
 *
 *   id         TEXT PRIMARY KEY
 *   noteId     TEXT     → whiteboards.note_id, nullable: a board created from Home stands on its
 *                         own, a board opened from a note belongs to it
 *   title      TEXT     user-given name, nullable (see NoteDisplayUtils.resolveWhiteboardTitle)
 *   createdAt  INTEGER
 *   updatedAt  INTEGER  bumped whenever the canvas changes, so Home can sort by recency
 *   background INTEGER  paper style: plain, yellowish, or dotted
 */
public class Whiteboard {
    public String id;
    public String noteId;
    public String title;
    public long createdAt;
    public long updatedAt;
    /** Paper style — see WhiteboardView.BACKGROUND_*. 0 (plain white) for every older board. */
    public int background;

    /** Not a column — joined in by WhiteboardRepository.loadWhiteboards for the Home card. */
    public int strokeCount;
}
