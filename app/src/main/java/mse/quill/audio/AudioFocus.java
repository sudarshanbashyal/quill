package mse.quill.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;

/**
 * One player's claim on the device's speakers, and the version split behind making it.
 *
 * <p>Both of the things Quill can play — a recording from a note, a recording inside a reading —
 * have to ask for focus the same way and give it back the same way, and the pre-O path needs the
 * listener kept for the abandon call while the post-O path needs the request object. That
 * bookkeeping is the whole of this class; the policy — what to do when focus is lost — stays with
 * the player, since a clip and a reading answer that differently.
 */
final class AudioFocus {

    private final AudioManager audioManager;
    private final Handler handler;

    private AudioFocusRequest request;

    AudioFocus(Context context, Handler handler) {
        this.audioManager = (AudioManager)
                context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        this.handler = handler;
    }

    /** @return whether playback may start. A device with no audio service is not a reason to
     *  refuse to play, so that case is treated as granted. */
    boolean request(AudioManager.OnAudioFocusChangeListener listener) {
        if (audioManager == null) return true;
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(speechAttributes())
                    .setOnAudioFocusChangeListener(listener, handler)
                    .build();
            result = audioManager.requestAudioFocus(request);
        } else {
            result = audioManager.requestAudioFocus(listener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    void abandon(AudioManager.OnAudioFocusChangeListener listener) {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (request != null) {
                audioManager.abandonAudioFocusRequest(request);
                request = null;
            }
        } else {
            audioManager.abandonAudioFocus(listener);
        }
    }

    /** Everything Quill plays is a person talking, and the system routes speech differently from
     *  music — quieter under a navigation prompt, louder on a call-oriented output. */
    static AudioAttributes speechAttributes() {
        return new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build();
    }
}
