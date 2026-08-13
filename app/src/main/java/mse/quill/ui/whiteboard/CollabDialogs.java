package mse.quill.ui.whiteboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.appcompat.app.AlertDialog;

import mse.quill.R;

/**
 * Dialogs for the live whiteboard collaboration flow. Built the same way {@code WhiteboardDialogs}
 * builds its dialogs — {@code MaterialAlertDialogBuilder} plus views assembled in code — since the
 * host/status screens here have nothing declared in {@code res/layout} to reuse.
 */
final class CollabDialogs {

    private CollabDialogs() {}

    interface EntryListener {
        void onHostChosen();
        void onJoinChosen();
    }

    static void showEntryDialog(Context context, EntryListener listener) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.collab_title)
                .setItems(new String[]{
                        context.getString(R.string.collab_host),
                        context.getString(R.string.collab_join)
                }, (dialog, which) -> {
                    if (which == 0) listener.onHostChosen();
                    else listener.onJoinChosen();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** A QR code plus a status line the caller updates as the peer connects, then dismisses. */
    static class StatusDialog {
        final AlertDialog dialog;
        private final TextView statusView;

        private StatusDialog(AlertDialog dialog, TextView statusView) {
            this.dialog = dialog;
            this.statusView = statusView;
        }

        void setStatus(String text) {
            statusView.setText(text);
        }

        void dismiss() {
            if (dialog.isShowing()) dialog.dismiss();
        }
    }

    /** Shown while hosting: the QR code to scan, and a status line ("waiting…" → "Connected"). */
    static StatusDialog showHostDialog(Context context, Bitmap qr, Runnable onCancelled) {
        int pad = dp(context, 24);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(pad, pad, pad, pad);

        ImageView qrView = new ImageView(context);
        int size = dp(context, 220);
        qrView.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        qrView.setImageBitmap(qr);
        column.addView(qrView);

        TextView status = new TextView(context);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, pad, 0, 0);
        status.setText(R.string.collab_hosting_waiting);
        column.addView(status);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.collab_host)
                .setView(column)
                .setNegativeButton(R.string.action_cancel, (d, w) -> onCancelled.run())
                .setCancelable(false)
                .show();
        return new StatusDialog(dialog, status);
    }

    /** Shown while joining, after the QR has already been scanned. */
    static StatusDialog showJoiningDialog(Context context, Runnable onCancelled) {
        int pad = dp(context, 24);
        TextView status = new TextView(context);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(pad, pad, pad, pad);
        status.setText(R.string.collab_connecting);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.collab_join)
                .setView(status)
                .setNegativeButton(R.string.action_cancel, (d, w) -> onCancelled.run())
                .setCancelable(false)
                .show();
        return new StatusDialog(dialog, status);
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
