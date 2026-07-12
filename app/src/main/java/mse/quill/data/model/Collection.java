package mse.quill.data.model;

public class Collection {
    public String id;
    public String name;
    public int color;
    public long createdAt;
    public boolean biometricLocked;

    /** Query-derived, not stored columns. */
    public int noteCount;
    public long lastActivityAt;
}
