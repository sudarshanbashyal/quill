package mse.quill.export;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * Handing a file Quill has written to whatever app the user picks.
 *
 * <p>Three screens do this — a note, a collection, a whiteboard — and all three wrote the same
 * four-line {@code ACTION_SEND} incantation out longhand, which is three chances to forget the one
 * line that matters. {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} is what makes the
 * {@code content://} uri readable by the target; without it the chooser opens, the user picks
 * something, and it fails on read with nothing to explain why.
 *
 * <p>Deliberately not a {@code Fragment} helper: it takes a {@link Context} and returns whether a
 * chooser opened, leaving each caller to report failure the way its screen already does — a
 * Snackbar in the note editor, a Toast on the other two.
 */
public final class ShareIntents {

    private ShareIntents() {}

    /**
     * Opens the system share sheet for a file Quill has just written.
     *
     * @param displayName the name to offer the target app, from {@code NoteExportStore.Saved}.
     * @return false if the device has nothing that can receive it, which the caller should say
     *         out loud — the share sheet simply not appearing looks like the button is broken.
     */
    public static boolean sendFile(Context context, Uri uri, String mimeType,
                                   String displayName, String chooserTitle) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TITLE, displayName)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return start(context, Intent.createChooser(send, chooserTitle));
    }

    /**
     * Opens a file Quill has written in whatever app claims its type — the "Open" half of the
     * export dialog, against {@link #sendFile}'s "Share".
     *
     * @return false if nothing on the device can view it.
     */
    public static boolean view(Context context, Uri uri, String mimeType) {
        return start(context, new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    private static boolean start(Context context, Intent intent) {
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }
}
