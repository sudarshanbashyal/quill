// TextSegment.java
package mse.quill.ui.notes.editor.model;

import android.text.Spannable;

public class TextSegment extends NoteSegment {
    public Spannable content;

    public TextSegment(Spannable content) {
        this.content = content;
        this.id = java.util.UUID.randomUUID().toString();
    }
}