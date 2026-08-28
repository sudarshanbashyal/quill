package mse.quill.ui.profile;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import android.text.format.DateFormat;

import java.util.Calendar;

import mse.quill.R;
import mse.quill.data.StudyHistory;
import mse.quill.ui.common.Haptics;
import mse.quill.data.AppExecutors;
import mse.quill.reminders.StudyReminders;
import mse.quill.security.AppLock;
import mse.quill.ui.splash.SplashActivity;
import mse.quill.data.DataWipe;
import mse.quill.ui.common.TextFieldUtils;

/**
 * The Profile tab: who Quill greets you as, whether it nags you, whether it locks, and the one
 * button that ends the notebook.
 *
 * <p>Everything here is a preference, so there is no repository and no loading state — the screen
 * renders straight from {@link ProfilePreferences} and {@link AppLock} and re-renders after each
 * change. {@link #onResume} re-reads them because the answer can change while the user is away:
 * enrolling a fingerprint (or removing the screen lock) in system settings decides whether the
 * app-lock row is offerable at all.
 */
public class ProfileFragment extends Fragment {

    private TextView avatar;
    private TextView avatarName;
    private TextView displayNameValue;
    private TextView appLockSummary;
    private MaterialSwitch appLockSwitch;
    private MaterialSwitch remindersSwitch;
    private TextView remindersSummary;
    private View reminderTimeRow;
    private View reminderTimeDivider;
    private TextView reminderTimeValue;
    private View remindersLockedNote;
    private View lockTimeoutRow;
    private View lockTimeoutDivider;
    private TextView lockTimeoutValue;

    /**
     * Asked for the first time the user turns reminders on, because that is the first moment the
     * permission means anything — the same posture the playback notification takes. A refusal
     * leaves the switch off rather than on-but-silent, which is the only honest reading of "you
     * may not notify me".
     */
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!isAdded()) return;
                if (granted) {
                    enableReminders();
                } else {
                    Snackbar.make(requireView(), R.string.profile_reminders_blocked,
                            Snackbar.LENGTH_LONG).show();
                    renderReminders();
                }
            });

    /** The grace-period choices, in the order the chooser lists them. */
    private static final long[] TIMEOUTS = {
            AppLock.GRACE_IMMEDIATELY,
            AppLock.GRACE_ONE_MINUTE,
            AppLock.GRACE_FIVE_MINUTES
    };

    private static final int[] TIMEOUT_LABELS = {
            R.string.profile_timeout_immediately,
            R.string.profile_timeout_one_minute,
            R.string.profile_timeout_five_minutes
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        avatar = view.findViewById(R.id.avatar);
        avatarName = view.findViewById(R.id.avatar_name);
        displayNameValue = view.findViewById(R.id.value_display_name);
        appLockSummary = view.findViewById(R.id.summary_app_lock);
        appLockSwitch = view.findViewById(R.id.switch_app_lock);
        remindersSwitch = view.findViewById(R.id.switch_reminders);
        remindersSummary = view.findViewById(R.id.summary_reminders);
        reminderTimeRow = view.findViewById(R.id.row_reminder_time);
        reminderTimeDivider = view.findViewById(R.id.divider_reminder_time);
        reminderTimeValue = view.findViewById(R.id.value_reminder_time);
        remindersLockedNote = view.findViewById(R.id.note_reminders_locked);
        lockTimeoutRow = view.findViewById(R.id.row_lock_timeout);
        lockTimeoutDivider = view.findViewById(R.id.divider_timeout);
        lockTimeoutValue = view.findViewById(R.id.value_lock_timeout);

        view.findViewById(R.id.row_display_name).setOnClickListener(v -> showDisplayNameDialog());
        view.findViewById(R.id.row_reminders).setOnClickListener(v -> toggleReminders());
        reminderTimeRow.setOnClickListener(v -> showReminderTimePicker());
        view.findViewById(R.id.row_app_lock).setOnClickListener(v -> toggleAppLock());
        lockTimeoutRow.setOnClickListener(v -> showTimeoutDialog());
        view.findViewById(R.id.row_delete_all).setOnClickListener(v -> confirmDeleteEverything());
    }

    @Override
    public void onResume() {
        super.onResume();
        render();
    }

    // ---------- Rendering ----------

    private void render() {
        renderIdentity();
        renderStudy();
        renderHaptics();
        renderReminders();
        renderAppLock();
    }

    /**
     * The streak and the calendar.
     *
     * <p>Read on every resume, like everything else here, because the answer changes while the user
     * is away — reviewing a deck and coming back to Profile should show the day already filled in
     * rather than the state it had before the session.
     *
     * <p>The wording never scolds. "Nothing today yet" says the streak is still standing rather
     * than that it is about to break, because a study app that opens with a threat is one people
     * stop opening.
     */
    private void renderStudy() {
        StudyHistory.load(requireContext(), history -> {
            if (!isAdded()) return;
            View root = requireView();
            TextView streak = root.findViewById(R.id.study_streak);
            TextView detail = root.findViewById(R.id.study_streak_detail);

            if (history.streakDays == 0) {
                streak.setText(R.string.profile_streak_none);
                detail.setText(R.string.profile_streak_none_detail);
            } else {
                streak.setText(getResources().getQuantityString(
                        R.plurals.profile_streak_days, history.streakDays, history.streakDays));
                detail.setText(history.reviewedToday > 0
                        ? getResources().getQuantityString(R.plurals.profile_streak_today,
                                history.reviewedToday, history.reviewedToday)
                        : getString(R.string.profile_streak_waiting));
            }

            ((StudyCalendarView) root.findViewById(R.id.study_calendar)).setHistory(history);
        });
    }

    /**
     * The haptics switch. Set without its listener attached, then wired — otherwise restoring the
     * stored state on every resume would read as the user having just flipped it, and the same
     * mistake would write the value back on each visit.
     */
    private void renderHaptics() {
        View root = requireView();
        MaterialSwitch toggle = root.findViewById(R.id.switch_haptics);
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(ProfilePreferences.hapticsEnabled(requireContext()));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            ProfilePreferences.setHapticsEnabled(requireContext(), checked);
            // Fired on the way on only: a tap that turns them off should be followed by silence,
            // which is both the setting working and the clearest possible confirmation of it.
            if (checked) Haptics.confirm(button);
        });
        root.findViewById(R.id.row_haptics).setOnClickListener(v -> toggle.toggle());
    }

    private void renderIdentity() {
        String name = ProfilePreferences.displayName(requireContext());

        // Falls back to the app's own initial rather than a person-shaped placeholder: an empty
        // circle belonging to nobody is better than one implying an account that doesn't exist.
        avatar.setText(name == null
                ? getString(R.string.profile_avatar_fallback)
                : name.substring(0, 1).toUpperCase());
        avatarName.setText(name == null ? getString(R.string.app_name) : name);
        displayNameValue.setText(name == null
                ? getString(R.string.profile_display_name_empty)
                : name);
    }

    // ---------- Study reminders ----------

    private void renderReminders() {
        boolean enabled = ProfilePreferences.notificationsEnabled(requireContext());
        remindersSwitch.setChecked(enabled);
        remindersSummary.setText(enabled
                ? R.string.profile_study_reminders_summary_on
                : R.string.profile_study_reminders_summary_off);

        int visibility = enabled ? View.VISIBLE : View.GONE;
        reminderTimeRow.setVisibility(visibility);
        reminderTimeDivider.setVisibility(visibility);
        remindersLockedNote.setVisibility(visibility);
        reminderTimeValue.setText(formatReminderTime());
    }

    /**
     * The reminder time in the device's own format, so a phone set to 12-hour clocks reads
     * "8:00 PM" rather than the 20:00 the preference stores.
     */
    private String formatReminderTime() {
        Calendar time = Calendar.getInstance();
        time.set(Calendar.HOUR_OF_DAY, ProfilePreferences.reminderHour(requireContext()));
        time.set(Calendar.MINUTE, ProfilePreferences.reminderMinute(requireContext()));
        return DateFormat.getTimeFormat(requireContext()).format(time.getTime());
    }

    private void toggleReminders() {
        if (ProfilePreferences.notificationsEnabled(requireContext())) {
            ProfilePreferences.setNotificationsEnabled(requireContext(), false);
            StudyReminders.cancel(requireContext());
            renderReminders();
            return;
        }

        // From API 33 the permission is what decides whether anything can be posted, so it is
        // asked for before the switch moves rather than after — a reminder that is on and mute
        // is worse than one that refused to turn on.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        enableReminders();
    }

    private void enableReminders() {
        ProfilePreferences.setNotificationsEnabled(requireContext(), true);
        StudyReminders.sync(requireContext());
        renderReminders();
    }

    private void showReminderTimePicker() {
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(DateFormat.is24HourFormat(requireContext())
                        ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                .setHour(ProfilePreferences.reminderHour(requireContext()))
                .setMinute(ProfilePreferences.reminderMinute(requireContext()))
                .setTitleText(R.string.profile_reminder_time_dialog_title)
                .build();

        picker.addOnPositiveButtonClickListener(v -> {
            ProfilePreferences.setReminderTime(requireContext(), picker.getHour(), picker.getMinute());
            // Re-scheduled, not just stored: the pending job still carries the old delay, and
            // without this the change wouldn't take effect until after one more run at the old
            // time — which is precisely the time the user has just said they don't want.
            StudyReminders.sync(requireContext());
            renderReminders();
        });

        picker.show(getParentFragmentManager(), "reminder_time");
    }

    // ---------- App lock ----------

    private void renderAppLock() {
        boolean available = AppLock.isAvailable(requireContext());
        boolean enabled = available && AppLock.isEnabled(requireContext());

        appLockSwitch.setChecked(enabled);
        appLockSwitch.setEnabled(available);
        requireView().findViewById(R.id.row_app_lock).setEnabled(available);

        appLockSummary.setText(!available
                ? R.string.profile_app_lock_unavailable
                : enabled ? R.string.profile_app_lock_summary_on
                : R.string.profile_app_lock_summary_off);

        int visibility = enabled ? View.VISIBLE : View.GONE;
        lockTimeoutRow.setVisibility(visibility);
        lockTimeoutDivider.setVisibility(visibility);
        lockTimeoutValue.setText(timeoutLabel(AppLock.graceMillis(requireContext())));
    }

    private String timeoutLabel(long millis) {
        for (int i = 0; i < TIMEOUTS.length; i++) {
            if (TIMEOUTS[i] == millis) return getString(TIMEOUT_LABELS[i]);
        }
        return getString(R.string.profile_timeout_one_minute);
    }

    // ---------- Display name ----------

    private void showDisplayNameDialog() {
        TextInputLayout field = TextFieldUtils.outlinedField(
                requireContext(), R.string.profile_display_name_hint);
        // Refuses the keystroke rather than accepting text and complaining about it on save.
        field.getEditText().setFilters(new InputFilter[]{DisplayName.filter()});
        field.setCounterEnabled(true);
        field.setCounterMaxLength(DisplayName.MAX_LENGTH);

        String current = ProfilePreferences.displayName(requireContext());
        if (current != null) {
            field.getEditText().setText(current);
            field.getEditText().setSelection(current.length());
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_display_name_dialog_title)
                .setMessage(R.string.profile_display_name_dialog_message)
                .setView(TextFieldUtils.inset(requireContext(), field))
                // No emptiness check, unlike the collection dialogs: clearing the field is a
                // legitimate way to say "don't greet me by name", and ProfilePreferences maps a
                // blank back to null for exactly that.
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    ProfilePreferences.setDisplayName(requireContext(),
                            field.getEditText().getText().toString());
                    renderIdentity();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ---------- App lock ----------

    /**
     * Turning the lock on asks for authentication first, and only writes the preference once it
     * succeeds.
     *
     * <p>Doing it in that order is what stops the switch locking someone out of their own notes:
     * if the enrolled fingerprint can't be read, or the prompt can't be shown on this device at
     * all, the failure happens here — with the app still open — rather than at the next cold start
     * with nothing but a gate to look at.
     *
     * <p>Turning it off is not gated. Whoever taps it has already passed the gate to reach this
     * screen, so a second prompt would defend nothing that isn't already open in front of them.
     */
    private void toggleAppLock() {
        if (!AppLock.isAvailable(requireContext())) return;

        if (AppLock.isEnabled(requireContext())) {
            AppLock.setEnabled(requireContext(), false);
            renderAppLock();
            return;
        }

        AppLock.authenticate(requireActivity(), new AppLock.Listener() {
            @Override public void onUnlocked() {
                if (!isAdded()) return;
                AppLock.setEnabled(requireContext(), true);
                renderAppLock();
            }

            @Override public void onFailed(int errorCode, CharSequence message) {
                if (!isAdded()) return;
                // The switch never moved — render() drives it from the preference, which is still
                // false — so there's nothing to revert, only something to say. Backing out of the
                // prompt is a decision, not an error, so it passes without comment.
                if (!AppLock.isUserCancellation(errorCode)) {
                    Snackbar.make(requireView(), R.string.profile_app_lock_failed,
                            Snackbar.LENGTH_LONG).show();
                }
                renderAppLock();
            }
        });
    }

    private void showTimeoutDialog() {
        long current = AppLock.graceMillis(requireContext());
        String[] labels = new String[TIMEOUTS.length];
        int checked = 0;
        for (int i = 0; i < TIMEOUTS.length; i++) {
            labels[i] = getString(TIMEOUT_LABELS[i]);
            if (TIMEOUTS[i] == current) checked = i;
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_timeout_dialog_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    AppLock.setGraceMillis(requireContext(), TIMEOUTS[which]);
                    renderAppLock();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ---------- Danger zone ----------

    /**
     * Two dialogs, the second of which has to be typed into.
     *
     * <p>A single "are you sure?" is dismissed by the same reflex that opened it, and this is the
     * one action in Quill with nothing behind it — no server copy, no trash, no undo. Making the
     * last step a word the user has to spell puts a deliberate act between them and an empty
     * notebook, which is the only protection an irreversible button can be given.
     */
    private void confirmDeleteEverything() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_delete_all_dialog_title)
                .setMessage(R.string.profile_delete_all_dialog_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.profile_delete_all_confirm,
                        (dialog, which) -> confirmDeleteEverythingByTyping())
                .show();
    }

    private void confirmDeleteEverythingByTyping() {
        TextInputLayout field = TextFieldUtils.outlinedField(
                requireContext(), R.string.profile_delete_all_hint);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_delete_all_final_title)
                .setMessage(R.string.profile_delete_all_final_message)
                .setView(TextFieldUtils.inset(requireContext(), field))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.profile_delete_all_confirm, (d, which) -> {
                    String typed = field.getEditText().getText().toString().trim();
                    if (typed.equalsIgnoreCase(getString(R.string.profile_delete_all_keyword))) {
                        wipeEverything();
                    }
                })
                .show();

        // Disabled until the word is right, rather than checked on press and silently ignored: the
        // button being dead is the feedback that the confirmation hasn't been given yet.
        View confirm = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        confirm.setEnabled(false);
        field.getEditText().addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void afterTextChanged(android.text.Editable s) {
                confirm.setEnabled(s.toString().trim()
                        .equalsIgnoreCase(getString(R.string.profile_delete_all_keyword)));
            }
        });
    }

    /**
     * Wipes on the disk thread, then relaunches into the splash.
     *
     * <p>The relaunch is not cosmetic. The wipe pulls the database out from under a running app
     * whose other screens are still on the back stack holding ids of rows that no longer exist —
     * popping back to Home would query them. {@code CLEAR_TASK} discards that stack entirely, so
     * what comes back is the app as it looks on a first install.
     */
    private void wipeEverything() {
        AppExecutors.getInstance().diskIO(() -> {
            DataWipe.wipeEverything(requireContext().getApplicationContext());
            AppExecutors.getInstance().mainThread(() -> {
                if (!isAdded()) return;
                Intent restart = new Intent(requireContext(), SplashActivity.class);
                restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(restart);
                requireActivity().finish();
            });
        });
    }
}
