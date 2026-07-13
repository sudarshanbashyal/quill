package mse.quill.util;

import android.content.Context;
import android.text.format.DateFormat;

import java.util.Date;

import mse.quill.R;
import mse.quill.data.model.Note;

public final class NoteDisplayUtils {

    private NoteDisplayUtils() {}

    /** Resolves what to show as a note's title: its title if set, else a generated
     *  "Untitled Note - <date>" placeholder using the note's creation date. */
    public static String resolveTitle(Context context, Note note) {
        boolean hasTitle = note.title != null && !note.title.trim().isEmpty();
        if (hasTitle) return note.title.trim();

        String date = DateFormat.getMediumDateFormat(context).format(new Date(note.createdAt));
        return context.getString(R.string.untitled_note_with_date, date);
    }
}
