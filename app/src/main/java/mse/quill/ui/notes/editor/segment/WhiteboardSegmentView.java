package mse.quill.ui.notes.editor.segment;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import mse.quill.R;
import mse.quill.data.model.NoteSegment;
import mse.quill.ui.whiteboard.WhiteboardThumbnails;
import mse.quill.util.NoteDisplayUtils;

/**
 * A whiteboard attached to a note, shown as a preview of the drawing.
 *
 * <p>A picture rather than a link, because a link tells you nothing about the board you drew: the
 * preview is the same render Home's cards use, so the note shows the actual thing. Tapping it
 * opens a sheet with the drawing and a way through to the board — going straight there on a tap
 * would make it too easy to leave the note by accident while writing.
 *
 * <p>Removing detaches, it doesn't delete: the board is its own thing on Home and may well be
 * attached elsewhere. The wording of the confirmation says so.
 */
public class WhiteboardSegmentView extends BaseSegmentView {

    /** Routed up because a segment view can't navigate — only the host fragment has the graph. */
    public interface OpenListener {
        void onOpenWhiteboard(String whiteboardId);
    }

    private final String whiteboardId;
    private final ImageView preview;
    private final TextView caption;
    private OpenListener openListener;
    private Bitmap thumbnail;
    private String boardName;

    public WhiteboardSegmentView(Context context, String whiteboardId) {
        super(context, whiteboardId);
        this.whiteboardId = whiteboardId;

        setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        int padding = getResources().getDimensionPixelSize(R.dimen.spacing_sm);
        setPadding(0, padding, 0, padding);

        preview = new ImageView(context);
        preview.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.note_whiteboard_preview_height)));
        // CENTER, not CENTER_CROP, while this is the fallback icon: cropping is right for a
        // drawing that should fill the box and absurd for a 24dp glyph, which it blew up until it
        // ran off both edges. loadPreview switches to CENTER_CROP if a real thumbnail arrives.
        preview.setScaleType(ImageView.ScaleType.CENTER);
        preview.setBackgroundColor(context.getColor(R.color.surface_container));
        preview.setImageResource(R.drawable.ic_section_whiteboard);
        preview.setOnClickListener(v -> showSheet());
        preview.setOnLongClickListener(v -> {
            confirmRemoval(null);
            return true;
        });
        addView(preview);

        caption = new TextView(context);
        LayoutParams captionParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        captionParams.topMargin = getResources().getDimensionPixelSize(R.dimen.spacing_xs);
        caption.setLayoutParams(captionParams);
        caption.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        caption.setAlpha(0.7f);
        addView(caption);

        loadPreview();
    }

    public void setOpenListener(OpenListener listener) {
        this.openListener = listener;
    }

    private void loadPreview() {
        WhiteboardThumbnails.loadById(getContext(), whiteboardId, (board, bitmap) -> {
            if (board == null) {
                // Deleted from Home since it was attached. Say so rather than showing a blank box.
                boardName = getContext().getString(R.string.whiteboard_missing);
                caption.setText(boardName);
                return;
            }
            boardName = NoteDisplayUtils.resolveWhiteboardTitle(getContext(), board);
            caption.setText(boardName);
            if (bitmap != null) {
                thumbnail = bitmap;
                preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                preview.setImageBitmap(bitmap);
            }
        });
    }

    /** The drawing, larger, with the two things you can do to it from inside a note. */
    private void showSheet() {
        ImageView large = new ImageView(getContext());
        large.setAdjustViewBounds(true);
        large.setScaleType(ImageView.ScaleType.FIT_CENTER);
        large.setBackgroundColor(getContext().getColor(R.color.surface_container));
        if (thumbnail != null) large.setImageBitmap(thumbnail);
        else large.setImageResource(R.drawable.ic_section_whiteboard);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(getContext())
                .setTitle(boardName != null ? boardName
                        : getContext().getString(R.string.section_whiteboards))
                .setView(large)
                .setPositiveButton(R.string.action_open_whiteboard, (d, which) -> {
                    if (openListener != null) openListener.onOpenWhiteboard(whiteboardId);
                })
                .setNeutralButton(R.string.action_remove_whiteboard, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.show();
        // Wired after show() so removing can ask for confirmation without the sheet closing
        // underneath the question — the default button behaviour would dismiss on the tap.
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> confirmRemoval(dialog::dismiss));
    }

    private void confirmRemoval(Runnable onConfirmed) {
        new MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.remove_whiteboard_title)
                .setMessage(R.string.remove_whiteboard_message)
                .setPositiveButton(R.string.action_remove, (dialog, which) -> {
                    if (onConfirmed != null) onConfirmed.run();
                    if (callback != null) callback.onRequestDelete(this);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    public String getWhiteboardId() {
        return whiteboardId;
    }

    @Override
    public int getSegmentType() {
        return NoteSegment.TYPE_WHITEBOARD;
    }

    @Override
    public Object getSegmentData() {
        return whiteboardId;
    }
}
