package mse.quill.bundle;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import mse.quill.data.NoteRepository;

/**
 * Packs a collection's notes into a {@link CollectionBundle}.
 *
 * <p>Runs on a disk thread, like {@link BundleWriter} — the caller has already read each note's
 * segments/tags synchronously (see {@link NoteRepository#loadForBundleSync}), so this only does
 * I/O: one nested {@code .quill} per note, then the manifest.
 */
public final class CollectionBundleWriter {

    private CollectionBundleWriter() {}

    public static void write(String name, int color, List<NoteRepository.NoteBundleData> notes,
                             Context context, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);

        int index = 0;
        for (NoteRepository.NoteBundleData note : notes) {
            ByteArrayOutputStream noteBytes = new ByteArrayOutputStream();
            BundleWriter.write(note.title, note.segments, note.tags,
                    note.createdAt, note.updatedAt, context, noteBytes);

            zip.putNextEntry(new ZipEntry(CollectionBundle.NOTES_DIR + index + "." + QuillBundle.EXTENSION));
            zip.write(noteBytes.toByteArray());
            zip.closeEntry();
            index++;
        }

        zip.putNextEntry(new ZipEntry(CollectionBundle.ENTRY_MANIFEST));
        zip.write(manifest(name, color, notes.size()).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();

        zip.finish();
    }

    private static String manifest(String name, int color, int noteCount) throws IOException {
        try {
            JSONObject manifest = new JSONObject();
            manifest.put(CollectionBundle.KEY_VERSION, CollectionBundle.SCHEMA_VERSION);
            manifest.put(CollectionBundle.KEY_NAME, name == null ? "" : name);
            manifest.put(CollectionBundle.KEY_COLOR, color);
            manifest.put(CollectionBundle.KEY_NOTE_COUNT, noteCount);
            return manifest.toString(2);
        } catch (JSONException e) {
            throw new IOException("could not build collection manifest", e);
        }
    }
}
