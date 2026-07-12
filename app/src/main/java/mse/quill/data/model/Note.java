package mse.quill.data.model;

public class Note {
    public String id;
    public String collectionId;
    public String title;
    public long createdAt;
    public long updatedAt;
    public Long deletedAt;

    /** Plain-text snippet derived from the first text segment, not a stored column. */
    public String preview;
}
