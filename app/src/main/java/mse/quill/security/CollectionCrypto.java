package mse.quill.security;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The actual encryption behind a locked collection: one Android Keystore key per collection, and
 * AES-GCM over the note text that key protects.
 *
 * <p><b>Why a Keystore key and not a password-derived one.</b> The key material never leaves the
 * TEE/StrongBox — this class only ever holds a handle to it — so a copy of {@code quill.db} taken
 * off the device is not decryptable anywhere else, by anyone, including with the user's PIN. That
 * is the property that makes this encryption rather than a second lock screen, and it is why the
 * feature borrows the device credential instead of inventing a Quill passcode: a key can be gated
 * on {@code setUserAuthenticationRequired(true)}, and nothing Quill could ask the user to type
 * would be.
 *
 * <p><b>Time-bound authentication, not per-use.</b> The alternative is binding each key to a single
 * {@code CryptoObject}, which means one system prompt per cipher operation — a fingerprint per note
 * in a collection someone is reading through. Instead the key is valid for
 * {@link #AUTH_VALIDITY_SECONDS} after any successful device authentication, so one prompt opens
 * the collection and reading it afterwards costs nothing. The window is the security trade being
 * made, and it is deliberately short: it is the period in which an unlocked, unattended phone
 * would still decrypt.
 *
 * <p>Two failure modes are worth distinguishing and are surfaced as separate exceptions, because
 * the UI does very different things with them: {@link NeedsAuthException} (the window has closed —
 * ask again) and {@link KeyGoneException} (the key is destroyed and the ciphertext is scrap).
 */
public final class CollectionCrypto {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_PREFIX = "quill.collection.";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** GCM's standard, and what {@link GCMParameterSpec} below is told to expect. */
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    /**
     * How long one authentication keeps the collection readable. Five minutes: long enough to read
     * and edit without being asked twice, short enough that a phone left on a desk re-locks before
     * anyone wanders past. {@link CollectionLock} also drops its session flag when the app is
     * backgrounded, so in practice the window usually ends earlier than this.
     */
    private static final int AUTH_VALIDITY_SECONDS = 300;

    private CollectionCrypto() {}

    /** The authentication window has expired (or never opened). Prompt and retry. */
    public static class NeedsAuthException extends GeneralSecurityException {
        NeedsAuthException(Throwable cause) { super(cause); }
    }

    /**
     * The key no longer exists or can no longer be used — the user removed their screen lock, or
     * the keystore entry was cleared. Anything it encrypted is unrecoverable; there is no second
     * copy of the key by design.
     */
    public static class KeyGoneException extends GeneralSecurityException {
        KeyGoneException(String message, Throwable cause) { super(message, cause); }
    }

    // ---------- Key lifecycle ----------

    public static boolean hasKey(String collectionId) throws GeneralSecurityException {
        try {
            return keyStore().containsAlias(alias(collectionId));
        } catch (Exception e) {
            throw new GeneralSecurityException("keystore unavailable", e);
        }
    }

    /**
     * Creates the collection's key, replacing any existing one.
     *
     * <p>Replacing matters: a collection that was locked, unlocked and locked again must not reuse
     * the old key, because "unlocked" decrypted everything back to plaintext and the old key's
     * remaining purpose was over. Generating a fresh one keeps each locked period cryptographically
     * separate.
     */
    public static void createKey(String collectionId) throws GeneralSecurityException {
        deleteKey(collectionId);

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(alias(collectionId),
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The modern form, and the only one that lets the *type* of accepted authentication be
            // stated. Naming both is what lets a phone with no enrolled fingerprint still open the
            // collection with its PIN — matching AppLock's prompt, which allows the same pair.
            spec.setUserAuthenticationParameters(AUTH_VALIDITY_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        } else {
            // Pre-30 there is no such choice: a duration-bound key is unlocked by any device
            // authentication, which is the behaviour wanted here anyway.
            spec.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS);
        }

        generator.init(spec.build());
        generator.generateKey();
    }

    public static void deleteKey(String collectionId) throws GeneralSecurityException {
        try {
            KeyStore keyStore = keyStore();
            if (keyStore.containsAlias(alias(collectionId))) {
                keyStore.deleteEntry(alias(collectionId));
            }
        } catch (Exception e) {
            throw new GeneralSecurityException("could not delete key", e);
        }
    }

    // ---------- Encrypt / decrypt ----------

    /**
     * @return the 12-byte IV followed by the ciphertext, which is what {@link #decrypt} expects.
     *     A fresh IV per call is not optional with GCM — reusing one under the same key leaks the
     *     relationship between the two plaintexts and undermines the authentication tag — so the
     *     one the provider generates is carried with the output rather than fixed anywhere.
     */
    public static byte[] encrypt(String collectionId, byte[] plaintext)
            throws GeneralSecurityException {
        if (plaintext == null) return null;
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key(collectionId));
        } catch (UserNotAuthenticatedException e) {
            throw new NeedsAuthException(e);
        } catch (KeyPermanentlyInvalidatedException e) {
            throw new KeyGoneException("collection key invalidated", e);
        }

        byte[] iv = cipher.getIV();
        byte[] body = cipher.doFinal(plaintext);

        byte[] out = new byte[iv.length + body.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(body, 0, out, iv.length, body.length);
        return out;
    }

    public static byte[] decrypt(String collectionId, byte[] payload)
            throws GeneralSecurityException {
        if (payload == null) return null;
        if (payload.length <= GCM_IV_BYTES) {
            throw new KeyGoneException("ciphertext too short to contain an IV", null);
        }

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_BYTES);
        try {
            cipher.init(Cipher.DECRYPT_MODE, key(collectionId), spec);
        } catch (UserNotAuthenticatedException e) {
            throw new NeedsAuthException(e);
        } catch (KeyPermanentlyInvalidatedException e) {
            throw new KeyGoneException("collection key invalidated", e);
        }

        return cipher.doFinal(
                Arrays.copyOfRange(payload, GCM_IV_BYTES, payload.length));
    }

    /**
     * Whether the collection's key can be used right now, without encrypting anything.
     *
     * <p>Used straight after a prompt to confirm the authentication actually reached the key,
     * rather than assuming it did because the prompt said "success" — the two are not the same
     * claim, and the gap between them is exactly where an unusable key would otherwise be
     * discovered only once the user had opened a note.
     */
    public static void assertUsable(String collectionId) throws GeneralSecurityException {
        encrypt(collectionId, new byte[]{0});
    }

    // ---------- Internals ----------

    private static SecretKey key(String collectionId) throws GeneralSecurityException {
        try {
            SecretKey key = (SecretKey) keyStore().getKey(alias(collectionId), null);
            if (key == null) {
                throw new KeyGoneException("no key for collection " + collectionId, null);
            }
            return key;
        } catch (KeyGoneException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException("could not load collection key", e);
        }
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        return keyStore;
    }

    private static String alias(String collectionId) {
        return KEY_PREFIX + collectionId;
    }
}
