package mse.quill.collab;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * The Bluetooth/location/Wi-Fi ladder Nearby needs, in one place.
 *
 * <p>Extracted from the whiteboard screen once Home gained a way to join a session too: two copies
 * of a version-gated permission list is two chances to update one of them and not the other, and
 * the list is exactly the thing that gets edited every time Android moves a permission.
 *
 * <p>The split is Android's own — see the notes in AndroidManifest.xml. Up to 30 the Bluetooth
 * permissions are unversioned and <em>location</em> gates BLE scanning; 31 renamed them into the
 * SCAN/ADVERTISE/CONNECT trio; 33 added NEARBY_WIFI_DEVICES so Wi-Fi peer discovery no longer has to
 * borrow location.
 */
public final class CollabPermissions {

    private CollabPermissions() {}

    /** Every permission this device's API level actually needs. */
    public static String[] required() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT <= 32) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        return permissions.toArray(new String[0]);
    }

    /** The subset not granted yet — empty when there is nothing to ask for. */
    public static String[] missing(Context context) {
        List<String> missing = new ArrayList<>();
        for (String permission : required()) {
            if (ContextCompat.checkSelfPermission(context, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing.toArray(new String[0]);
    }
}
