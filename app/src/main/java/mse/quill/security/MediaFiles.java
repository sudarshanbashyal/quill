package mse.quill.security;

import android.media.MediaDataSource;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Encryption for the files behind a note's images and recordings.
 *
 * <p>The note text of a locked collection has been encrypted for a while; its media never was.
 * They were unreachable through the UI while the collection was shut, which is not the same
 * property at all — the bytes sat in {@code filesDir} in the clear, so a photo of a passport in a
 * locked collection was protected by the app's navigation rather than by its key.
 *
 * <p><b>The file says whose it is.</b> An encrypted file is a short header — the magic
 * {@code QLM1}, then the owning collection's id — followed by whatever
 * {@link CollectionCrypto#encrypt} produced. That is what keeps this from spreading through the
 * codebase: every decode site has a path and nothing else, and with a self-describing file it can
 * stay that way. No lookup from path to segment to note to collection, no {@code Context} threaded
 * into {@code BitmapUtils}, and no way for the two to disagree about which key a file needs.
 *
 * <p><b>Plaintext files stay readable.</b> Everything already on disk has no header, and so does
 * every file belonging to an unlocked collection. {@link #readPlaintext} checks for the magic
 * rather than being told what to expect, which is what makes locking, unlocking and moving a note
 * between collections safe to run more than once.
 *
 * <p><b>Nothing is ever written out decrypted.</b> The deferral note that put this off warned that
 * a version writing decrypted temp files and forgetting to clean them up would be worse than
 * leaving the media in the clear, since it would add copies while claiming to remove them. So there
 * is no temp file anywhere here: images decode from a byte array, and audio — which genuinely needs
 * random access — gets {@link #source}, a {@link MediaDataSource} over the plaintext held in
 * memory. Both {@code MediaPlayer} and {@code MediaExtractor} accept one.
 */
public final class MediaFiles {

    private static final String TAG = "MediaFiles";
    private static final byte[] MAGIC = {'Q', 'L', 'M', '1'};
    /** Collection ids are UUIDs, so one length byte is more than enough. */
    private static final int MAX_ID_LENGTH = 255;

    private MediaFiles() {}

    /** Whether this file carries the header — i.e. belongs to a collection that is locked. */
    public static boolean isEncrypted(String path) {
        return isEncrypted(new File(path));
    }

    public static boolean isEncrypted(File file) {
        byte[] head = new byte[MAGIC.length];
        try (FileInputStream in = new FileInputStream(file)) {
            return in.read(head) == MAGIC.length && Arrays.equals(head, MAGIC);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The file's contents in the clear, whether or not it is encrypted.
     *
     * <p>Returns null rather than throwing when the key won't open — the callers are decode sites,
     * and every one of them already has to handle "this didn't decode" for a corrupt or deleted
     * file. A collection whose authentication window has closed reads the same way: nothing shows
     * until it is unlocked again.
     */
    public static byte[] readPlaintext(String path) {
        File file = new File(path);
        byte[] raw = readAll(file);
        if (raw == null) return null;
        if (!startsWithMagic(raw)) return raw;

        try {
            int idLength = raw[MAGIC.length] & 0xFF;
            int payloadStart = MAGIC.length + 1 + idLength;
            if (payloadStart > raw.length) return null;
            String collectionId = new String(
                    raw, MAGIC.length + 1, idLength, StandardCharsets.UTF_8);
            byte[] payload = Arrays.copyOfRange(raw, payloadStart, raw.length);
            return CollectionCrypto.decrypt(collectionId, payload);
        } catch (GeneralSecurityException | RuntimeException e) {
            Log.w(TAG, "could not decrypt media file " + path, e);
            return null;
        }
    }

    /**
     * A seekable view of the decrypted bytes, or null when the file is plaintext and the caller
     * should just use the path.
     *
     * <p>Null rather than a source over the raw file on purpose: handing {@code MediaPlayer} a path
     * lets it stream from disk, which is the better path when there is nothing to hide.
     */
    public static MediaDataSource source(String path) {
        File file = new File(path);
        if (!isEncrypted(file)) return null;
        byte[] plaintext = readPlaintext(path);
        if (plaintext == null) return null;
        return new ByteArraySource(plaintext);
    }

    /**
     * Encrypts the file in place, for {@code collectionId}. A file that already has the header is
     * left alone, so locking a collection twice — or locking one whose media was written while it
     * was already locked — is not a way to double-encrypt anything.
     */
    public static void encrypt(String path, String collectionId) throws GeneralSecurityException {
        File file = new File(path);
        if (!file.exists() || isEncrypted(file)) return;
        byte[] plaintext = readAll(file);
        if (plaintext == null) return;

        byte[] idBytes = collectionId.getBytes(StandardCharsets.UTF_8);
        if (idBytes.length > MAX_ID_LENGTH) {
            throw new GeneralSecurityException("collection id too long to store in a media header");
        }
        byte[] payload = CollectionCrypto.encrypt(collectionId, plaintext);

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                MAGIC.length + 1 + idBytes.length + payload.length);
        out.write(MAGIC, 0, MAGIC.length);
        out.write(idBytes.length);
        out.write(idBytes, 0, idBytes.length);
        out.write(payload, 0, payload.length);
        writeAll(file, out.toByteArray());
    }

    /** Decrypts the file in place. A plaintext file is left alone, for the same reason as above. */
    public static void decrypt(String path) throws GeneralSecurityException {
        File file = new File(path);
        if (!file.exists() || !isEncrypted(file)) return;
        byte[] plaintext = readPlaintext(path);
        if (plaintext == null) {
            throw new GeneralSecurityException("media file could not be decrypted: " + path);
        }
        writeAll(file, plaintext);
    }

    private static boolean startsWithMagic(byte[] raw) {
        if (raw.length < MAGIC.length + 1) return false;
        for (int i = 0; i < MAGIC.length; i++) {
            if (raw[i] != MAGIC[i]) return false;
        }
        return true;
    }

    private static byte[] readAll(File file) {
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        } catch (IOException e) {
            Log.w(TAG, "could not read media file " + file, e);
            return null;
        }
    }

    /**
     * Replaces the file's contents.
     *
     * <p>Written through a sibling and renamed over the original: a conversion interrupted halfway
     * — by a crash, or by the process being killed mid-lock — would otherwise leave a file that is
     * neither the plaintext nor the ciphertext, and nothing could tell which half it had.
     */
    private static void writeAll(File file, byte[] contents) {
        File temp = new File(file.getParentFile(), file.getName() + ".converting");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(contents);
            out.getFD().sync();
        } catch (IOException e) {
            Log.w(TAG, "could not write media file " + file, e);
            temp.delete();
            return;
        }
        if (!temp.renameTo(file)) {
            Log.w(TAG, "could not replace media file " + file);
            temp.delete();
        }
    }

    /** Random access over bytes already in memory — what audio needs and a byte array can't give. */
    private static final class ByteArraySource extends MediaDataSource {
        private final byte[] data;

        ByteArraySource(byte[] data) {
            this.data = data;
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int size) {
            if (position >= data.length) return -1;
            int available = (int) Math.min(size, data.length - position);
            System.arraycopy(data, (int) position, buffer, offset, available);
            return available;
        }

        @Override
        public long getSize() {
            return data.length;
        }

        @Override
        public void close() {
            // Nothing to release: the bytes are the caller's and go when it does.
        }
    }
}
