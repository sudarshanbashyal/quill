package mse.quill.ui.notes.editor;

import mse.quill.data.model.HeadingMarker;

/**
 * What the toolbar should show right now: which formats are on, and which are even offered.
 *
 * <p>A value object rather than a long positional argument list, because the two halves come from
 * different places and are easy to confuse. {@link #bold}/{@link #italic}/{@link #underline} are a
 * *pending typing mode*; {@link #headingLevel}/{@link #bullet} describe *the line the caret is in*;
 * and the {@code …Allowed} flags describe *the field the caret is in* — a Q&amp;A question or answer
 * refuses headings and embeds, so those controls grey out while the caret sits there.
 */
public final class FormattingState {

    public boolean bold;
    public boolean italic;
    public boolean underline;
    public boolean bullet;
    public int headingLevel = HeadingMarker.NONE;

    public boolean headingsAllowed = true;
    public boolean embedsAllowed = true;

    /** Nothing editable is focused — show everything off, and offer nothing. */
    public static FormattingState none() {
        FormattingState state = new FormattingState();
        state.headingsAllowed = false;
        state.embedsAllowed = false;
        return state;
    }
}
