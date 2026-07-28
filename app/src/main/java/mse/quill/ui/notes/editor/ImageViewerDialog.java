package mse.quill.ui.notes.editor;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import mse.quill.R;
import mse.quill.util.BitmapUtils;

/**
 * Full-screen image viewer over a dimmed scrim, with save and delete.
 *
 * <p>Deliberately a bare {@link Dialog} rather than a MaterialAlertDialog: the point is an
 * edge-to-edge image on a blacked-out background, and an alert dialog's inset card and surface
 * colour work against that. The buttons inside are still MDC.
 */
public class ImageViewerDialog {

    public interface ActionListener {
        void onSaveRequested();
        void onDeleteRequested();
    }

    /** Wide enough to stay sharp on any phone, still sampled so a large image can't OOM here. */
    private static final int VIEWER_MAX_WIDTH = 1440;
    private static final int SCRIM_COLOR = 0xE6000000;

    private final Dialog dialog;
    private final FrameLayout root;

    public ImageViewerDialog(Context context, String filePath, ActionListener listener) {
        root = new FrameLayout(context);
        root.setBackgroundColor(SCRIM_COLOR);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView image = new ImageView(context);
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        int inset = dp(context, 16);
        imageParams.setMargins(inset, inset, inset, dp(context, 88));
        image.setLayoutParams(imageParams);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription(context.getString(R.string.image_viewer_description));
        Bitmap bitmap = BitmapUtils.decodeSampled(filePath, VIEWER_MAX_WIDTH);
        if (bitmap != null) image.setImageBitmap(bitmap);
        root.addView(image);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.gravity = Gravity.BOTTOM;
        actionParams.bottomMargin = dp(context, 24);
        actions.setLayoutParams(actionParams);
        root.addView(actions);

        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);

        Window window = dialog.getWindow();
        if (window != null) {
            // Without both of these the dialog keeps its default inset card background and
            // wrap-content size, and the scrim only covers part of the screen.
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }

        // Neither action closes the viewer itself. Saving reports back through
        // {@link #showMessage}, and deleting only closes once the confirmation is accepted —
        // dismissing on the tap would hide the image behind the "are you sure?" prompt.
        addAction(context, actions, R.string.action_save_image, v -> listener.onSaveRequested());
        addAction(context, actions, R.string.action_delete, v -> listener.onDeleteRequested());

        // Tapping the backdrop (but not the image or the buttons) closes the viewer.
        root.setOnClickListener(v -> dialog.dismiss());
    }

    private void addAction(Context context, LinearLayout container, int labelRes,
                           View.OnClickListener action) {
        MaterialButton button = new MaterialButton(context, null,
                com.google.android.material.R.attr.borderlessButtonStyle);
        button.setText(labelRes);
        button.setTextColor(Color.WHITE);
        button.setOnClickListener(action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(context, 8), 0, dp(context, 8), 0);
        container.addView(button, params);
    }

    /** Feedback has to be anchored inside this dialog's own window — a Snackbar on the editor's
     *  root view is behind it and would never be seen while the viewer is up. */
    public void showMessage(int messageRes) {
        if (!dialog.isShowing()) return;
        Snackbar.make(root, messageRes, Snackbar.LENGTH_SHORT).show();
    }

    public void show() {
        dialog.show();
    }

    public void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
