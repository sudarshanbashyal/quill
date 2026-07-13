package mse.quill.ui.notes.editor.model;

public abstract class NoteSegment {
    public String id;
    public float position;

    public static final int TYPE_TEXT  = 0;
    public static final int TYPE_IMAGE = 1;
    public static final int TYPE_AUDIO = 2;
}