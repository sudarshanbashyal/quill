package mse.quill.audio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

import mse.quill.R;
import mse.quill.ui.splash.SplashActivity;

/**
 * Keeps a recording playing when Quill isn't on screen — a locked phone, another app, the app
 * swiped away from view — and puts the controls where the user can reach them there.
 *
 * <p>Two separate mechanisms, both needed. The <em>foreground service</em> is what stops the
 * system from freezing or killing the process once the app stops being visible; without it a clip
 * gets a few seconds past the lock screen and then goes quiet. The <em>media session</em> is what
 * puts a play/pause card on the lock screen and in the notification shade, and what makes
 * headset and Bluetooth buttons work.
 *
 * <p>The service holds no player of its own. It reflects {@link AudioPlayback} into a notification
 * and forwards button presses back — see that class for why the player sits outside it.
 */
public class AudioPlaybackService extends Service {

    static final String ACTION_TOGGLE = "mse.quill.audio.TOGGLE";
    static final String ACTION_CLOSE = "mse.quill.audio.CLOSE";
    private static final String ACTION_REFRESH = "mse.quill.audio.REFRESH";

    private static final String CHANNEL_ID = "quill_audio_playback";
    private static final int NOTIFICATION_ID = 1;

    private MediaSession mediaSession;

    static void start(Context context) {
        context.startForegroundService(new Intent(context, AudioPlaybackService.class)
                .setAction(ACTION_REFRESH));
    }

    /** Updates the notification in place if the service is up; never starts one that isn't. */
    static void refresh(Context context) {
        AudioPlayback playback = AudioPlayback.peek();
        if (playback == null || !playback.hasClip()) return;
        context.startService(new Intent(context, AudioPlaybackService.class)
                .setAction(ACTION_REFRESH));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, AudioPlaybackService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        mediaSession = new MediaSession(this, "QuillAudio");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { AudioPlayback.handleAction(AudioPlaybackService.this, ACTION_TOGGLE); }
            @Override public void onPause() { AudioPlayback.handleAction(AudioPlaybackService.this, ACTION_TOGGLE); }
            @Override public void onStop() { AudioPlayback.handleAction(AudioPlaybackService.this, ACTION_CLOSE); }
            @Override public void onSeekTo(long pos) {
                AudioPlayback playback = AudioPlayback.peek();
                if (playback != null) playback.seekTo((int) pos);
            }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_REFRESH : intent.getAction();
        if (ACTION_TOGGLE.equals(action) || ACTION_CLOSE.equals(action)) {
            AudioPlayback.handleAction(this, action);
            // close() stops the service; a toggle falls through to redraw the button.
        }

        AudioPlayback playback = AudioPlayback.peek();
        if (playback == null || !playback.hasClip()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        publishSessionState(playback);
        Notification notification = buildNotification(playback);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        // Not sticky: a restarted service with no player would be a foreground notification for
        // audio that isn't playing.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Nothing binds: the player is reachable directly.
    }

    /** What the lock screen reads: the title, how long the clip is, and where it is up to. */
    private void publishSessionState(AudioPlayback playback) {
        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titleOf(playback))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, playback.durationMs())
                .build());
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO)
                .setState(playback.isPlaying()
                                ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        playback.currentPositionMs(), 1f)
                .build());
    }

    private Notification buildNotification(AudioPlayback playback) {
        boolean playing = playback.isPlaying();

        Notification.Action toggle = new Notification.Action.Builder(
                icon(playing ? R.drawable.ic_pause : R.drawable.ic_play),
                getString(playing ? R.string.action_pause_audio : R.string.action_play_audio),
                servicePendingIntent(ACTION_TOGGLE)).build();
        Notification.Action close = new Notification.Action.Builder(
                icon(R.drawable.ic_clear),
                getString(R.string.action_close_audio),
                servicePendingIntent(ACTION_CLOSE)).build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(titleOf(playback))
                .setContentText(getString(R.string.audio_notification_text))
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(openAppIntent())
                .setDeleteIntent(servicePendingIntent(ACTION_CLOSE))
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                // Public, so the controls are usable on a secured lock screen instead of being
                // replaced by "contents hidden" — the note's title is the only thing on show, and
                // reaching pause without unlocking is the whole point of it being there.
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(toggle)
                .addAction(close)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1))
                .build();
    }

    private android.graphics.drawable.Icon icon(int drawableRes) {
        return android.graphics.drawable.Icon.createWithResource(this, drawableRes);
    }

    private String titleOf(AudioPlayback playback) {
        String title = playback.currentTitle();
        return title == null || title.trim().isEmpty()
                ? getString(R.string.audio_notification_fallback_title) : title;
    }

    private PendingIntent servicePendingIntent(String action) {
        return PendingIntent.getService(this, action.hashCode(),
                AudioPlayback.intentFor(this, action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Tapping the notification returns to Quill. It goes through the launcher entry point so an
     *  app that isn't running comes up the normal way rather than into a bare editor. */
    private PendingIntent openAppIntent() {
        Intent intent = new Intent(this, SplashActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.audio_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.audio_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }
}
