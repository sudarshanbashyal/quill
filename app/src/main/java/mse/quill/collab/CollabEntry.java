package mse.quill.collab;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import android.widget.Toast;

import mse.quill.R;

/**
 * Getting into a collaboration session: the Nearby permission ladder, the QR scan, and the one
 * dialog that explains a scan going wrong.
 *
 * <p>Three places start a join — the whiteboard's Collaborate button, Home's FAB, and a
 * {@code quill://} link — and all three used to implement this separately. Home's copy was the
 * whiteboard's minus the "scan again" button, which is the shape duplication rots into: not a
 * disagreement anyone decided on, just one copy that never got the improvement.
 *
 * <p>This class stops at the token. What happens next genuinely differs between callers, so
 * handing back a token and getting out of the way is the whole contract.
 *
 * <p><b>Construct before the fragment reaches STARTED</b> — a field initialiser or
 * {@code onCreate}. It registers a permission launcher, and doing that later throws. Same
 * constraint, and same reason, as {@code export.StoragePermission}.
 */
public final class CollabEntry {

    public interface OnToken {
        void onToken(String token);
    }

    private final Fragment fragment;
    private final ActivityResultLauncher<String[]> permissionLauncher;

    /** What to do once the prompt resolves. Null except while it is up. */
    private Runnable pendingAction;

    public CollabEntry(Fragment fragment) {
        this.fragment = fragment;
        this.permissionLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), results -> {
                    boolean allGranted = !results.containsValue(false);
                    Runnable action = pendingAction;
                    pendingAction = null;
                    if (allGranted && action != null) {
                        action.run();
                    } else if (!allGranted && fragment.isAdded()) {
                        Toast.makeText(fragment.requireContext(),
                                R.string.collab_permission_denied, Toast.LENGTH_LONG).show();
                    }
                });
    }

    /**
     * Runs {@code action} once the Nearby ladder is satisfied — the Bluetooth/location/Wi-Fi set
     * documented in AndroidManifest.xml, version-gated so a device only sees prompts for
     * permissions it actually has.
     *
     * <p>Idempotent: calling it when everything is already granted runs {@code action} inline, so
     * a caller that has already climbed the ladder can call again without a second prompt.
     */
    public void withPermission(Runnable action) {
        if (!fragment.isAdded()) return;
        String[] missing = CollabPermissions.missing(fragment.requireContext());
        if (missing.length == 0) {
            action.run();
            return;
        }
        pendingAction = action;
        permissionLauncher.launch(missing);
    }

    /** Permission, then the scanner, then the token — or the error dialog, and nothing. */
    public void scanForToken(OnToken onToken) {
        withPermission(() -> scan(onToken));
    }

    private void scan(OnToken onToken) {
        if (!fragment.isAdded()) return;
        SessionScanner.scan(fragment.requireContext(), new SessionScanner.Listener() {
            @Override public void onToken(String token) {
                if (fragment.isAdded()) onToken.onToken(token);
            }

            @Override public void onCancelled() {
                // Backing out of the scanner is an answer, not a fault. Nothing was started.
            }

            @Override public void onFailed(boolean notASession) {
                showError(notASession
                        ? R.string.collab_error_not_a_session
                        : R.string.collab_error_scanner, notASession, onToken);
            }
        });
    }

    /**
     * A dialog rather than a toast: every one of these is a dead end the user has to decide
     * something about, and a message that fades after two seconds is not that.
     *
     * @param offerScanAgain adds a second button straight back to the scanner, for the failures
     *                       where trying the same code again is the obvious next move.
     */
    private void showError(int messageRes, boolean offerScanAgain, OnToken onToken) {
        if (!fragment.isAdded()) return;
        MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(fragment.requireContext())
                        .setTitle(R.string.collab_error_title)
                        .setMessage(messageRes)
                        .setPositiveButton(android.R.string.ok, null);
        if (offerScanAgain) {
            builder.setNeutralButton(R.string.collab_scan_again, (d, w) -> scan(onToken));
        }
        builder.show();
    }
}
