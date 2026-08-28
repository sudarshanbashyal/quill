package mse.quill.ui.notes;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import mse.quill.R;
import mse.quill.bundle.BundleWriter;
import mse.quill.bundle.QuillBundle;
import mse.quill.data.AppExecutors;
import mse.quill.data.model.NoteSegment;
import mse.quill.data.model.Tag;
import mse.quill.export.ImageExporter;
import mse.quill.export.MarkdownExporter;
import mse.quill.export.NoteExportStore;
import mse.quill.export.PdfExporter;
import mse.quill.export.ShareIntents;
import mse.quill.ui.notes.editor.segment.BaseSegmentView;

/**
 * Every way a note leaves Quill: as a PDF, as Markdown, as a {@code .quill} bundle, and — for one
 * embedded picture at a time — into the device's gallery.
 *
 * <p>Split out of {@code NoteEditorFragment}, which was 1298 lines with roughly 240 of them here.
 * The division is the one {@code WhiteboardCollabController} uses: this class knows about formats,
 * files and the share sheet, and nothing about editing; the fragment knows about editing and hands
 * over the note's current state through {@link Host} when asked.
 *
 * <p>Nothing is saved before an export. What leaves is what is on screen right now, not what was
 * last written — an export never lags a keystroke behind — and unlike a deck or a quiz this does
 * not need the note to have a row, so exporting should not be the thing that creates one.
 */
final class NoteExportController {

    /**
     * What this needs from the editor. Deliberately all pull, not push: the controller asks at the
     * moment of export, so there is no copy of the note here to go stale.
     */
    interface Host {

        /** The note as it stands on screen. */
        List<NoteSegment> segmentsForExport();

        /** The title as it stands on screen, already clipped to a filename-safe length. */
        String titleForExport();

        /** Tags travel in a bundle; they are not in the segments. */
        List<Tag> tagsForExport();

        /** Zero for a note that has never been saved — the bundle then stamps "now". */
        long createdAtForExport();

        /** True while the note's collection is locked, which stops a bundle leaving the device. */
        boolean isCollectionLocked();

        /**
         * Runs {@code onGranted} once {@code WRITE_EXTERNAL_STORAGE} is in hand, or
         * {@code onDenied} if it is refused. The launcher has to be registered by the fragment,
         * which is the only reason this is not done here.
         */
        void requestStoragePermission(Runnable onGranted, Runnable onDenied);
    }

    /** PDF and Markdown leave Quill for another tool and are lossy on purpose; BUNDLE leaves for
     *  another Quill and is not. See {@link QuillBundle}. */
    private enum Format { PDF, MARKDOWN, BUNDLE }

    private final Fragment fragment;
    private final Host host;
    private final AppExecutors executors = AppExecutors.getInstance();

    NoteExportController(Fragment fragment, Host host) {
        this.fragment = fragment;
        this.host = host;
    }

    // ── The menu ──────────────────────────────────────────────────────────────

    /** The formats a note can leave in. Anchored to the same button as the options menu, so it
     *  reads as that menu going one level deeper rather than as an unrelated popup. */
    void showExportMenu(View anchor) {
        PopupMenu menu = new PopupMenu(fragment.requireContext(), anchor);
        menu.inflate(R.menu.menu_note_export);
        menu.setForceShowIcon(true);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_export_pdf) {
                exportNote(Format.PDF);
                return true;
            }
            if (id == R.id.action_export_markdown) {
                exportNote(Format.MARKDOWN);
                return true;
            }
            if (id == R.id.action_export_bundle) {
                // A locked collection's notes don't leave the device — a bundle is plaintext, so
                // sharing one would be the lock's only hole. The item stays tappable rather than
                // being disabled or hidden, because a greyed-out row explains nothing and a missing
                // one reads as a feature Quill doesn't have; the tap is what carries the reason.
                if (host.isCollectionLocked()) {
                    snack(R.string.share_locked_collection, Snackbar.LENGTH_LONG);
                    return true;
                }
                exportNote(Format.BUNDLE);
                return true;
            }
            return false;
        });
        menu.show();
    }

    // ── Exporting the note ────────────────────────────────────────────────────

    /**
     * Writes the note out as a file in Downloads/Quill.
     *
     * <p>The segments are read on the main thread and the file written on the disk thread — see
     * the class comment for why nothing is saved first.
     */
    private void exportNote(Format format) {
        List<NoteSegment> segments = host.segmentsForExport();
        String title = host.titleForExport();
        if (segments.isEmpty() && title.trim().isEmpty()) {
            snack(R.string.export_empty, Snackbar.LENGTH_SHORT);
            return;
        }
        Runnable write = () -> writeExport(format, title, segments);
        if (ImageExporter.requiresStoragePermission()) {
            host.requestStoragePermission(write, () -> {});
            return;
        }
        write.run();
    }

    private void writeExport(Format format, String title, List<NoteSegment> segments) {
        Context appContext = fragment.requireContext().getApplicationContext();
        String audioLabel = fragment.getString(R.string.export_audio_placeholder);
        String imageLabel = fragment.getString(R.string.export_image_placeholder);
        // Read here, on the main thread, for the same reason the segments are: a bundle is of the
        // note as it stands, and both lists belong to the UI.
        List<Tag> tags = new ArrayList<>(host.tagsForExport());
        long created = host.createdAtForExport();
        long createdAt = created > 0 ? created : System.currentTimeMillis();

        executors.diskIO(() -> {
            NoteExportStore.Saved saved;
            if (format == Format.PDF) {
                saved = NoteExportStore.save(appContext, title, PdfExporter.EXTENSION,
                        PdfExporter.MIME_TYPE,
                        out -> PdfExporter.write(title, segments, audioLabel, out));
            } else if (format == Format.BUNDLE) {
                saved = NoteExportStore.save(appContext, title, QuillBundle.EXTENSION,
                        QuillBundle.MIME_TYPE,
                        out -> BundleWriter.write(title, segments, tags, createdAt,
                                System.currentTimeMillis(), appContext, out));
            } else {
                String markdown = MarkdownExporter.toMarkdown(title, segments, audioLabel, imageLabel);
                saved = NoteExportStore.save(appContext, title, MarkdownExporter.EXTENSION,
                        MarkdownExporter.MIME_TYPE,
                        out -> out.write(markdown.getBytes(StandardCharsets.UTF_8)));
            }
            executors.mainThread(() -> {
                if (!fragment.isAdded()) return;
                if (saved == null) snack(R.string.export_failed, Snackbar.LENGTH_LONG);
                else showExportComplete(format, saved);
            });
        });
    }

    // ── Confirming it ─────────────────────────────────────────────────────────

    /**
     * Confirms an export and offers to open it.
     *
     * <p>A dialog rather than the Snackbar this replaced: the useful action here is opening the
     * file, and a Snackbar takes that away again after a few seconds — long enough to miss, and
     * unrecoverable once gone since nothing in the app lists past exports.
     *
     * <p>The animation is deliberately small — the badge springs in and the two lines follow it up.
     * It exists to make the moment land, not to be watched, so it is over in a third of a second
     * and the buttons are usable throughout.
     */
    private void showExportComplete(Format format, NoteExportStore.Saved saved) {
        View content = fragment.getLayoutInflater().inflate(R.layout.dialog_export_complete, null);
        ((ImageView) content.findViewById(R.id.export_badge_icon)).setImageResource(
                badgeIconFor(format));
        ((TextView) content.findViewById(R.id.export_filename)).setText(
                fragment.getString(R.string.export_complete_location, saved.displayName));
        // A bundle isn't "exported" in the sense the other two are — nothing on the device will
        // display it, and the point of making it was to send it somewhere.
        boolean bundle = format == Format.BUNDLE;
        if (bundle) {
            ((TextView) content.findViewById(R.id.export_title))
                    .setText(R.string.export_bundle_complete_title);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(fragment.requireContext())
                .setView(content)
                // Same button, different verb: Open for a file a viewer can show, Share for one
                // only another copy of Quill can read. The file is saved in Downloads either way,
                // so dismissing here loses nothing.
                .setPositiveButton(bundle ? R.string.action_share_export
                                          : R.string.action_open_export,
                        (d, which) -> {
                            if (bundle) shareExport(saved);
                            else openExport(saved, format);
                        })
                .setNegativeButton(R.string.action_done, null)
                .create();
        dialog.setOnShowListener(d -> animateExportDialog(content));
        dialog.show();
    }

    /** Badge springs in, then the title and filename rise into place a beat behind it. */
    private void animateExportDialog(View content) {
        View badge = content.findViewById(R.id.export_badge);
        badge.setScaleX(0.6f);
        badge.setScaleY(0.6f);
        badge.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(320)
                .setInterpolator(new OvershootInterpolator(2f))
                .start();

        float rise = fragment.getResources().getDimension(R.dimen.export_dialog_gap);
        int delay = 90;
        for (int id : new int[]{R.id.export_title, R.id.export_filename}) {
            View line = content.findViewById(id);
            line.setTranslationY(rise);
            line.animate().alpha(1f).translationY(0f)
                    .setStartDelay(delay)
                    .setDuration(220)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            delay += 60;
        }
    }

    private static int badgeIconFor(Format format) {
        if (format == Format.PDF) return R.drawable.ic_pdf;
        if (format == Format.BUNDLE) return R.drawable.ic_share;
        return R.drawable.ic_markdown;
    }

    // ── Getting it somewhere ──────────────────────────────────────────────────

    /**
     * Hands the exported file to whatever on the device can display it.
     *
     * <p>Markdown is tried twice. Almost nothing registers for {@code text/markdown} — a stock
     * emulator image has no handler for it at all, while every device has something for
     * {@code text/plain} — so the accurate type is offered first and the readable one is the
     * fallback. Without that, Open on a Markdown export was a button that could only ever fail.
     */
    private void openExport(NoteExportStore.Saved saved, Format format) {
        if (format == Format.PDF) {
            if (!view(saved.uri, PdfExporter.MIME_TYPE)) snack(R.string.export_no_viewer, Snackbar.LENGTH_LONG);
            return;
        }
        if (!view(saved.uri, MarkdownExporter.MIME_TYPE) && !view(saved.uri, "text/plain")) {
            snack(R.string.export_no_viewer, Snackbar.LENGTH_LONG);
        }
    }

    /**
     * Hands the bundle to the system share sheet.
     *
     * <p>This is the whole of Quill's transport story for a shared note, and deliberately so.
     * Quick Share, Bluetooth and mail are share <em>targets</em>, not APIs — there is nothing to
     * integrate with, and building any of it by hand would be re-implementing a chooser the system
     * already draws. What arrives on the other phone is a file, which the receiving Quill imports
     * through the picker.
     */
    private void shareExport(NoteExportStore.Saved saved) {
        boolean opened = ShareIntents.sendFile(fragment.requireContext(), saved.uri,
                QuillBundle.MIME_TYPE, saved.displayName,
                fragment.getString(R.string.share_export_chooser));
        if (!opened) snack(R.string.share_no_target, Snackbar.LENGTH_LONG);
    }

    private boolean view(Uri uri, String mimeType) {
        return ShareIntents.view(fragment.requireContext(), uri, mimeType);
    }

    // ── One embedded picture, into the gallery ────────────────────────────────

    /**
     * Copies an embedded file out to the shared Pictures collection.
     *
     * <p>Below API 29 that needs {@code WRITE_EXTERNAL_STORAGE}, and a segment view has no way to
     * ask for a runtime permission — which is why this is reached from the editor at all rather
     * than done where the tap lands.
     */
    void exportMedia(String filePath, BaseSegmentView.ExportResult result) {
        if (filePath == null || result == null) return;
        Runnable save = () -> {
            Context appContext = fragment.requireContext().getApplicationContext();
            executors.diskIO(() -> {
                // A name back means it was written; null means nothing was.
                boolean saved = ImageExporter.saveToPictures(appContext, filePath) != null;
                executors.mainThread(() -> result.onExportFinished(saved));
            });
        };
        if (ImageExporter.requiresStoragePermission()) {
            // Refused is an answer, and the segment view is still showing a spinner waiting on one.
            host.requestStoragePermission(save, () -> result.onExportFinished(false));
            return;
        }
        save.run();
    }

    private void snack(int textRes, int duration) {
        if (!fragment.isAdded() || fragment.getView() == null) return;
        Snackbar.make(fragment.requireView(), textRes, duration).show();
    }
}
