package mse.quill.ui.notes.editor.segment;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import mse.quill.R;
import mse.quill.ui.notes.editor.ImageViewerDialog;
import mse.quill.data.model.NoteSegment;
import mse.quill.util.BitmapUtils;

public class ImageSegmentView extends BaseSegmentView {

    private final ImageView imageView;
    private final String filePath;
    private final int displayWidth;

    public ImageSegmentView(Context context, String segmentId, String filePath, int displayWidth) {
        super(context, segmentId);
        this.filePath = filePath;
        this.displayWidth = displayWidth;

        setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        setOrientation(VERTICAL);
        // Centres the image in the note. Needed on the container as well as the scale type: with
        // adjustViewBounds the ImageView shrinks to the scaled image's width, so centring the
        // content inside the view isn't enough on its own.
        setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        imageView = new ImageView(context);
        imageView.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // Caps how much of the note a tall image can occupy — with adjustViewBounds the image
        // scales down to fit rather than being cropped, and the full-size view is a tap away.
        imageView.setMaxHeight(getResources().getDimensionPixelSize(R.dimen.note_image_max_height));

        // Sampled to roughly the display width: a phone capture decoded at full resolution is
        // tens of megabytes of heap, and several in one note is an OOM waiting to happen.
        imageView.setImageBitmap(BitmapUtils.decodeSampled(filePath, thumbnailWidth()));

        imageView.setOnClickListener(v -> showViewer());

        // Long press → confirm before deleting (there's no keyboard gesture for this, so it must
        // not be a single accidental long-press away from losing the image).
        imageView.setOnLongClickListener(v -> {
            showDeleteConfirmation();
            return true;
        });

        addView(imageView);
    }

    private int thumbnailWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    /** Opens the image full-screen, where it can be saved out or deleted. The viewer stays up
     *  through both actions — saving reports back into it, and deleting closes it only once the
     *  confirmation is accepted. */
    private void showViewer() {
        ImageViewerDialog[] viewer = new ImageViewerDialog[1];
        viewer[0] = new ImageViewerDialog(getContext(), filePath, new ImageViewerDialog.ActionListener() {
            @Override public void onSaveRequested() {
                if (callback == null) return;
                callback.onRequestExport(ImageSegmentView.this, saved ->
                        viewer[0].showMessage(saved ? R.string.image_saved : R.string.image_save_failed));
            }
            @Override public void onDeleteRequested() {
                showDeleteConfirmation(viewer[0]::dismiss);
            }
        });
        viewer[0].show();
    }

    private void showDeleteConfirmation() {
        showDeleteConfirmation(null);
    }

    /** @param onConfirmed run before the delete itself — used to close the viewer, but only once
     *                     the user has actually confirmed rather than on the Delete tap. */
    private void showDeleteConfirmation(Runnable onConfirmed) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.delete_image_title)
                .setMessage(R.string.delete_image_message)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    if (onConfirmed != null) onConfirmed.run();
                    if (callback != null) callback.onRequestDelete(this);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    public String getFilePath() { return filePath; }

    /** Carried through unchanged so it survives the save round-trip — the display width lives on
     *  the asset row because Markdown link syntax has nowhere to put it. */
    public int getDisplayWidth() { return displayWidth; }

    @Override
    public int getSegmentType() { return NoteSegment.TYPE_IMAGE; }

    @Override
    public Object getSegmentData() { return filePath; }
}