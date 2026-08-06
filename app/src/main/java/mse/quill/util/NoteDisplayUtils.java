package mse.quill.util;

import android.content.Context;
import android.text.format.DateFormat;

import java.util.Date;

import mse.quill.R;
import mse.quill.data.model.Note;
import mse.quill.model.Whiteboard;

public final class NoteDisplayUtils {

    private NoteDisplayUtils() {}

    /** Resolves what to show as a note's title: its title if set, else a generated
     *  "Untitled Note - <date>" placeholder using the note's creation date. */
    public static String resolveTitle(Context context, Note note) {
        boolean hasTitle = note.title != null && !note.title.trim().isEmpty();
        if (hasTitle) return note.title.trim();

        return untitledWithDate(context, note.createdAt);
    }

    /** "Untitled Note - <date>" for the given timestamp — shared by the display fallback above
     *  and by the note editor, which pre-fills a brand new note's title field with this so it
     *  reads the same whether or not the user ever types a title of their own. */
    public static String untitledWithDate(Context context, long timestampMs) {
        String date = DateFormat.getMediumDateFormat(context).format(new Date(timestampMs));
        return context.getString(R.string.untitled_note_with_date, date);
    }

    /** The same fallback for whiteboards, which are unnamed until the user renames one. */
    public static String resolveWhiteboardTitle(Context context, Whiteboard whiteboard) {
        boolean hasTitle = whiteboard.title != null && !whiteboard.title.trim().isEmpty();
        if (hasTitle) return whiteboard.title.trim();

        String date = DateFormat.getMediumDateFormat(context).format(new Date(whiteboard.createdAt));
        return context.getString(R.string.untitled_whiteboard_with_date, date);
    }
}
