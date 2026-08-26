// QaSegment.java
package mse.quill.data.model;

import android.text.Spannable;
import android.text.SpannableStringBuilder;

/**
 * A question/answer block. Two independently formatted regions rather than one — a flashcard is
 * generated from the pair, so the split has to survive the round trip through storage rather than
 * being inferred from prose later.
 */
public class QaSegment extends NoteSegment {

    public Spannable question;
    public Spannable answer;

    public QaSegment(Spannable question, Spannable answer) {
        this.question = question != null ? question : new SpannableStringBuilder("");
        this.answer = answer != null ? answer : new SpannableStringBuilder("");
        this.id = java.util.UUID.randomUUID().toString();
    }

    @Override
    public int type() { return TYPE_QA; }
}
