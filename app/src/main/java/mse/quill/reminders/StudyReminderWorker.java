package mse.quill.reminders;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import mse.quill.MainActivity;
import mse.quill.R;
import mse.quill.data.FlashcardRepository;
import mse.quill.ui.profile.ProfilePreferences;

/**
 * The daily reminder itself: count what's due, say so if there is anything, and re-arm for
 * tomorrow.
 *
 * <p>The re-arming happens in a {@code finally}, and that placement is the point — a reminder that
 * stopped scheduling itself because one run threw would be a feature that silently died. Whatever
 * happens to this run, tomorrow's is queued.
 */
public class StudyReminderWorker extends Worker {

    private static final String CHANNEL_ID = "quill_study_reminders";
    /** Fixed, so a second reminder replaces the first rather than stacking up unread. */
    private static final int NOTIFICATION_ID = 4201;

    public StudyReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        try {
            // Checked again here, not just when the work was scheduled: the switch may have been
            // turned off while this run was pending, and a cancelled job is not guaranteed to
            // vanish before it fires.
            if (!ProfilePreferences.notificationsEnabled(context)) return Result.success();

            FlashcardRepository.DueSummary due =
                    new FlashcardRepository(context).countDueSync(System.currentTimeMillis());

            // Nothing due is the common case for anyone keeping up, and it is not worth a
            // notification. A daily "you have 0 cards due" is how a reminder trains someone to
            // ignore it — and then to switch it off.
            if (!due.isEmpty()) notify(context, due);

            return Result.success();
        } finally {
            StudyReminders.sync(context);
        }
    }

    private void notify(Context context, FlashcardRepository.DueSummary due) {
        // Below API 33 notifications need no runtime grant; from 33 a refused permission means
        // posting is a silent no-op, so it's skipped rather than pretended.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        createChannel(context, manager);

        String title = context.getResources().getQuantityString(
                R.plurals.reminder_cards_due, due.cards, due.cards);
        String text = context.getResources().getQuantityString(
                R.plurals.reminder_across_decks, due.decks, due.decks);

        manager.notify(NOTIFICATION_ID, new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_flashcard)
                .setContentIntent(openFlashcards(context))
                .setAutoCancel(true)
                // Private, unlike the playback notification: how far behind someone is on their
                // revision is nobody else's business, and this one has no controls that would
                // justify showing it on a locked screen.
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build());
    }

    /**
     * Tapping it lands on the Flashcards tab rather than wherever the app was left. Straight to
     * {@link MainActivity} rather than through the splash: this is a resumption of something the
     * user was asked about, and two seconds of logo animation between the tap and the decks would
     * be the app admiring itself.
     */
    private PendingIntent openFlashcards(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_FLASHCARDS, true)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createChannel(Context context, NotificationManager manager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return;
        // DEFAULT, not LOW: this one is meant to be noticed once a day, which is the difference
        // between it and the playback channel's silent, ongoing status row.
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.reminder_channel_description));
        manager.createNotificationChannel(channel);
    }
}
