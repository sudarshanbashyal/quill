package mse.quill.export;

import android.Manifest;
import android.content.pm.PackageManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

/**
 * The {@code WRITE_EXTERNAL_STORAGE} ladder that guards writing into a shared MediaStore
 * collection.
 *
 * <p>Only API 26–28 ever climbs it. Scoped storage (29+) lets an app add its own entries
 * unprompted, which is why {@code minSdk 26} is the only reason this class exists at all — and
 * why forgetting it is easy: on any modern device the un-permissioned path works fine and the
 * omission shows up only on hardware the developer probably isn't holding. That is exactly how
 * the whiteboard's export shipped broken (see {@code memory/refactoring_plan.md} R10).
 *
 * <p><b>Why a class and not a static helper.</b> {@link Fragment#registerForActivityResult} has to
 * be called before the fragment reaches STARTED, so the launcher cannot be created lazily at the
 * point of use — every screen that wanted this had to declare a launcher field, a pending-action
 * field, and the same fifteen lines of ladder. Constructing this in a field initialiser or
 * {@code onCreate} registers the launcher at the right moment and keeps all three together.
 */
public final class StoragePermission {

    private final Fragment fragment;
    private final ActivityResultLauncher<String> launcher;

    /** Both outcomes are held: the two things that queue behind this permission — exporting a note
     *  and saving one picture — want different things when it is refused. */
    private Runnable onGranted;
    private Runnable onDenied;

    /** <b>Construct before the fragment reaches STARTED</b> — a field initialiser or
     *  {@code onCreate}. Registering a launcher later throws. */
    public StoragePermission(Fragment fragment) {
        this.fragment = fragment;
        this.launcher = fragment.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    Runnable granted_ = onGranted;
                    Runnable denied = onDenied;
                    onGranted = null;
                    onDenied = null;
                    if (granted) {
                        if (granted_ != null) granted_.run();
                    } else if (denied != null) {
                        denied.run();
                    }
                });
    }

    /**
     * Runs {@code onGranted} — immediately if this device does not need the permission or already
     * has it, otherwise after the prompt — or {@code onDenied} if it is refused.
     */
    public void require(Runnable onGranted, Runnable onDenied) {
        if (!ImageExporter.requiresStoragePermission()
                || ContextCompat.checkSelfPermission(fragment.requireContext(),
                        Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            == PackageManager.PERMISSION_GRANTED) {
            onGranted.run();
            return;
        }
        this.onGranted = onGranted;
        this.onDenied = onDenied;
        launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }
}
