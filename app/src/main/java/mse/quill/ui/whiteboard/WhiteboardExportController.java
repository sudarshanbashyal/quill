package mse.quill.ui.whiteboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import java.util.List;

import mse.quill.R;
import mse.quill.bundle.WhiteboardBundle;
import mse.quill.bundle.WhiteboardBundleWriter;
import mse.quill.data.AppExecutors;
import mse.quill.data.StrokeRepository;
import mse.quill.data.WhiteboardRepository;
import mse.quill.data.WhiteboardTextRepository;
import mse.quill.data.model.Stroke;
import mse.quill.data.model.Whiteboard;
import mse.quill.data.model.WhiteboardText;
import mse.quill.export.ImageExporter;
import mse.quill.export.NoteExportStore;
import mse.quill.export.ShareIntents;
import mse.quill.export.StoragePermission;

/**
 * The two ways a whiteboard leaves Quill: as a flat PNG in the gallery, or as a
 * {@code .quillboard} bundle handed to the share sheet.
 *
 * <p>Split out of {@code WhiteboardFragment} to match {@link mse.quill.ui.notes.NoteExportController}
 * — the same job on the other screen, which had a controller since 2026-08-26 while this stayed
 * inline. The two are deliberately <em>not</em> one class: they share the permission ladder (now
 * {@link StoragePermission}) and the share-sheet call (now {@code ShareIntents}), but their
 * formats and where their content comes from genuinely differ. A note's export reads segments off
 * the editor; a board's reads three tables and renders a bitmap.
 *
 * <p>The lossy/lossless split is the same on both screens, and that is the point of the menu: a
 * PNG is a picture of the board for anyone, a bundle is the strokes and text another Quill can
 * redraw and keep editing.
 */
final class WhiteboardExportController {

    private static final String TAG = "WhiteboardExport";

    /** What this needs from the screen, asked for at the moment of export so nothing goes stale. */
    interface Host {

        /** The board's row id. */
        String boardId();

        /** The title as typed, or empty to fall back to whatever the row holds. */
        String typedTitle();

        /** The canvas as it looks right now, or null if the view has gone. */
        Bitmap renderBoard();

        /** The board's one line of feedback — see {@code WhiteboardFragment.showTransientMessage}. */
        void showMessage(int textRes);
    }

    private final Fragment fragment;
    private final Host host;
    private final StoragePermission storagePermission;
    private final AppExecutors executors = AppExecutors.getInstance();

    private final WhiteboardRepository whiteboardRepo;
    private final StrokeRepository strokeRepo;
    private final WhiteboardTextRepository textRepo;

    WhiteboardExportController(Fragment fragment, Host host, StoragePermission storagePermission) {
        this.fragment = fragment;
        this.host = host;
        this.storagePermission = storagePermission;
        Context context = fragment.requireContext();
        this.whiteboardRepo = new WhiteboardRepository(context);
        this.strokeRepo = new StrokeRepository(context);
        this.textRepo = new WhiteboardTextRepository(context);
    }

    /** Export as a flat image (lossy — a picture of the board) or share the board itself (lossless
     *  — the strokes and text another Quill can redraw and keep editing), mirroring the choice a
     *  note's Export menu already offers between PDF/Markdown and a {@code .quill} bundle. */
    void showExportMenu(View anchor) {
        PopupMenu menu = new PopupMenu(fragment.requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.whiteboard_export_image);
        menu.getMenu().add(0, 2, 1, R.string.whiteboard_share);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) exportImage();
            else shareBundle();
            return true;
        });
        menu.show();
    }

    /**
     * Renders the current canvas into the device's Pictures/Quill album.
     *
     * <p>Goes through {@link ImageExporter} rather than talking to MediaStore here. This used to
     * have its own copy of that, and the copy had drifted: it set {@code RELATIVE_PATH}
     * unconditionally (an API 29 column, against {@code minSdk 26}), never asked for
     * {@code WRITE_EXTERNAL_STORAGE} where API 26-28 requires it, and skipped {@code IS_PENDING}
     * so a gallery scanning mid-write could catch a half-drawn board.
     */
    private void exportImage() {
        // Rendered before the permission prompt, not after: the prompt takes the window, and the
        // board behind it is what the user asked to export.
        Bitmap bitmap = host.renderBoard();
        if (bitmap == null) return;
        storagePermission.require(() -> writeImage(bitmap), () -> {});
    }

    private void writeImage(Bitmap bitmap) {
        Context appContext = fragment.requireContext().getApplicationContext();
        executors.diskIO(() -> {
            String savedAs = ImageExporter.savePngToPictures(appContext, bitmap);
            executors.mainThread(() -> {
                if (!fragment.isAdded()) return;
                if (savedAs == null) {
                    Log.e(TAG, "whiteboard export failed");
                    host.showMessage(R.string.whiteboard_export_failed);
                    return;
                }
                Toast.makeText(fragment.requireContext(),
                        fragment.getString(R.string.whiteboard_export_saved,
                                ImageExporter.albumPath(), savedAs),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    /**
     * Packs the board into a {@code .quillboard} bundle and hands it to the system share sheet —
     * the same {@link ShareIntents#sendFile} path a note's "Share to another Quill" uses, since
     * Quick Share, Bluetooth and mail are share <em>targets</em> here too, not APIs to integrate
     * with.
     */
    private void shareBundle() {
        final String id = host.boardId();
        final String typed = host.typedTitle();
        final Context appContext = fragment.requireContext().getApplicationContext();
        executors.diskIO(() -> {
            Whiteboard board = whiteboardRepo.getByIdSync(id);
            if (board == null) return;
            List<Stroke> strokes = strokeRepo.getByWhiteboardSync(id);
            List<WhiteboardText> texts = textRepo.getByWhiteboardSync(id);
            String title = typed.isEmpty() ? board.title : typed;

            NoteExportStore.Saved saved = NoteExportStore.save(
                    appContext,
                    title == null ? "" : title,
                    WhiteboardBundle.EXTENSION,
                    WhiteboardBundle.MIME_TYPE,
                    out -> WhiteboardBundleWriter.write(
                            title, board.background, board.createdAt, board.updatedAt,
                            strokes, texts, out));

            executors.mainThread(() -> {
                if (!fragment.isAdded()) return;
                if (saved == null) {
                    toast(R.string.share_failed, Toast.LENGTH_SHORT);
                    return;
                }
                boolean opened = ShareIntents.sendFile(fragment.requireContext(), saved.uri,
                        WhiteboardBundle.MIME_TYPE, saved.displayName,
                        fragment.getString(R.string.whiteboard_share_chooser));
                if (!opened) toast(R.string.share_no_target, Toast.LENGTH_LONG);
            });
        });
    }

    private void toast(int textRes, int duration) {
        Toast.makeText(fragment.requireContext(), textRes, duration).show();
    }
}
