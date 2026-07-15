// AudioSegment.java
package mse.quill.ui.notes.editor.model;

public class AudioSegment extends NoteSegment {
    public String filePath;
    public int durationMs;

    public AudioSegment(String filePath, int durationMs) {
        this.filePath = filePath;
        this.durationMs = durationMs;
        this.id = java.util.UUID.randomUUID().toString();
    }
}
