// TextSegment.java
package mse.quill.data.model;

import android.text.Spannable;

public class TextSegment extends NoteSegment {
    public Spannable content;

    public TextSegment(Spannable content) {
        this.content = content;
        this.id = java.util.UUID.randomUUID().toString();
    }

    @Override
    public int type() { return TYPE_TEXT; }
}
