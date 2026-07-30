// ImageSegment.java
package mse.quill.ui.notes.editor.model;

public class ImageSegment extends NoteSegment {
    public String filePath;
    public int displayWidth;

    public ImageSegment(String filePath) {
        this.filePath = filePath;
        this.id = java.util.UUID.randomUUID().toString();
    }

    @Override
    public int type() { return TYPE_IMAGE; }

    @Override
    public String filePath() { return filePath; }
}
