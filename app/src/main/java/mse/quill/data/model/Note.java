package mse.quill.data.model;

import java.util.ArrayList;
import java.util.List;

public class Note {
    public String id;
    public String collectionId;
    public String title;
    public long createdAt;
    public long updatedAt;
    public Long deletedAt;
    public Long pinnedAt;

    /** Plain-text snippet derived from the first text segment, not a stored column. */
    public String preview;

    /** Query-derived, not a stored column. */
    public List<Tag> tags = new ArrayList<>();
}
