package mse.quill.util;

import android.content.Context;
import android.text.format.DateFormat;

import java.util.Date;

import mse.quill.R;
import mse.quill.data.model.Note;
import mse.quill.data.model.Whiteboard;

public final class NoteDisplayUtils {

    private NoteDisplayUtils() {}

    /** Resolves what to show as a note's title: its title if set, else a generated
     *  "Untitled Note - <date>" placeholder using the note's creation date. */
    public static String resolveTitle(Context context, Note note) {
        return resolveTitle(context, note.title, note.createdAt);
    }

    /**
     * The same fallback for the screens that hold a note's title and creation date without holding
     * the note — a flashcard deck, a quiz. An untitled note stores an empty title on purpose (the
     * editor offers the generated name as a hint rather than typing it in for you), so every list
     * that shows one has to resolve it, or the row arrives with no name at all.
     */
    public static String resolveTitle(Context context, String title, long createdAt) {
        boolean hasTitle = title != null && !title.trim().isEmpty();
        if (hasTitle) return title.trim();

        return untitledWithDate(context, createdAt);
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
