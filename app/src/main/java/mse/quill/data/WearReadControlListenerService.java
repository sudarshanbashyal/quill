package mse.quill.data;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import mse.quill.audio.ReadAloud;

/**
 * Pauses, resumes and stops a reading, because the watch asked.
 *
 * <p>The other half of {@link WearReadListenerService}. That one starts a voice on the phone from a
 * tap on the wrist; without this one, the only way to stop it again was to unlock the phone and
 * find Quill's now-playing bar — which made starting a reading from the watch a thing you would
 * think twice about doing.
 *
 * <p>Nothing here decides anything. {@link ReadAloud} already knows whether it is speaking or
 * paused, and the watch's button sends the same toggle the phone's own bar sends, so the two
 * controls cannot end up meaning different things.
 */
public class WearReadControlListenerService extends WearableListenerService {

    private static final String TAG = "WearReadControl";

    @Override
    public void onMessageReceived(@NonNull MessageEvent event) {
        if (!ReadControlKeys.PATH.equals(event.getPath())) return;

        DataMap map;
        try {
            map = DataMap.fromByteArray(event.getData());
        } catch (RuntimeException e) {
            Log.w(TAG, "Dropping a read control with an unreadable payload", e);
            return;
        }

        String action = map.getString(ReadControlKeys.KEY_ACTION);
        if (action == null) {
            Log.w(TAG, "Dropping a read control with no action");
            return;
        }

        // On the main thread: ReadAloud is a process-wide singleton the UI also drives, and
        // TextToSpeech posts its callbacks there.
        new Handler(Looper.getMainLooper()).post(() -> {
            // Before acting, not after: the toggle and the stop both change what the watch's
            // buttons should say, and ReadAloud's listeners are what carry that back — but only if
            // something is listening, and a phone that was never opened has nothing attached yet.
            WearReadStatePublisher.ensureAttached(getApplicationContext());

            switch (action) {
                case ReadControlKeys.ACTION_TOGGLE:
                    // A no-op when nothing is being read, which is the right answer: the watch was
                    // a moment behind, not mistaken.
                    ReadAloud.togglePause();
                    break;
                case ReadControlKeys.ACTION_STOP:
                    ReadAloud.stop();
                    break;
                default:
                    Log.w(TAG, "Ignoring an unknown read control: " + action);
                    break;
            }
        });
    }
}
