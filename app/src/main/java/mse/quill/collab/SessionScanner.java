package mse.quill.collab;

import android.content.Context;
import android.util.Log;

import com.google.mlkit.common.MlKitException;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

/**
 * Scanning a host's QR code and getting back a token that is actually a token.
 *
 * <p>Shared by the two places a session can be joined from — Home's menu and the whiteboard
 * screen's own collaborate button — so that both check the scan the same way. The scanner UI is
 * Play Services' own, running in its process, which is why Quill never holds the CAMERA permission.
 */
public final class SessionScanner {

    private static final String TAG = "SessionScanner";

    public interface Listener {
        void onToken(String token);

        /** The user backed out of the scanner. Nothing went wrong and nothing should be said. */
        void onCancelled();

        /** @param notASession true when something was scanned and it wasn't a Quill code — as
         *                     opposed to the scanner itself failing. */
        void onFailed(boolean notASession);
    }

    private SessionScanner() {}

    public static void scan(Context context, Listener listener) {
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(context, options);
        scanner.startScan()
                .addOnSuccessListener(barcode -> {
                    // Checked before anything is started for it: any QR at all used to be taken as
                    // a token, so scanning a poster began a search that could never succeed.
                    String token = SessionCode.parse(barcode.getRawValue());
                    if (token == null) listener.onFailed(true);
                    else listener.onToken(token);
                })
                .addOnFailureListener(e -> {
                    // Backing out of the scanner arrives here too, so "the user changed their
                    // mind" and "the scanner could not run" used to be reported identically —
                    // which is how pressing back produced an error about a connection nobody had
                    // asked for yet. Play Services does not help as much as it looks: on this
                    // build a back-press comes back as the generic INTERNAL (13) with "Failed to
                    // scan code", not the CODE_SCANNER_CANCELLED the API documents.
                    //
                    // So the question is turned around. Only the codes that say the scanner itself
                    // could not start are worth a dialog; everything else means no code was
                    // scanned, and the right response to that is to do nothing at all.
                    int code = e instanceof MlKitException
                            ? ((MlKitException) e).getErrorCode() : -1;
                    if (!worthReporting(code)) {
                        Log.d(TAG, "no code scanned (error code " + code + ")");
                        listener.onCancelled();
                        return;
                    }
                    Log.w(TAG, "scanner unavailable (error code " + code + ")", e);
                    listener.onFailed(false);
                });
    }

    /**
     * Whether this is the scanner failing rather than the user leaving it.
     *
     * <p>Everything listed here is a reason the scanner could not be shown at all, which the user
     * cannot work out for themselves and may be able to fix. Cancellation, a scan already in
     * progress, and the generic codes are all "no code came back" — the same outcome as walking
     * away from it, and just as unremarkable.
     */
    private static boolean worthReporting(int errorCode) {
        switch (errorCode) {
            case MlKitException.CODE_SCANNER_UNAVAILABLE:
            case MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED:
            case MlKitException.CODE_SCANNER_APP_NAME_UNAVAILABLE:
            case MlKitException.CODE_SCANNER_PIPELINE_INITIALIZATION_ERROR:
            case MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD:
                return true;
            default:
                return false;
        }
    }

}
