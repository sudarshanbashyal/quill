package mse.quill.ui.notes.editor;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import mse.quill.R;

/** Modal "recording in progress" popup. Cancelable only through its own Stop button — not the
 *  back button or a tap outside — so the rest of the editor is unreachable and a recording can't
 *  be silently abandoned mid-capture. Shows a live elapsed-time counter and amplitude waveform,
 *  both driven by the caller polling {@link AudioRecorder} and pushing readings via
 *  {@link #update(int, int)}. */
public class RecordingDialog {

    public interface StopListener { void onStopRequested(); }

    private final AlertDialog dialog;
    private final TextView timerText;
    private final WaveformView waveformView;

    public RecordingDialog(Context context, StopListener listener) {
        int padLg = dp(context, 24);
        int padMd = dp(context, 16);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(padLg, padLg, padLg, padLg);

        TextView title = new TextView(context);
        title.setText(R.string.recording_in_progress_title);
        title.setTextColor(context.getColor(R.color.text_secondary));
        content.addView(title);

        timerText = new TextView(context);
        timerText.setText(R.string.recording_timer_placeholder);
        timerText.setTextSize(36);
        timerText.setTextColor(context.getColor(R.color.text_primary));
        LinearLayout.LayoutParams timerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        timerParams.topMargin = padMd;
        timerText.setLayoutParams(timerParams);
        content.addView(timerText);

        waveformView = new WaveformView(context);
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 64));
        waveParams.topMargin = padMd;
        waveformView.setLayoutParams(waveParams);
        content.addView(waveformView);

        Button stopButton = new Button(context);
        stopButton.setText(R.string.action_stop_recording);
        stopButton.setAllCaps(false);
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stopParams.topMargin = padLg;
        stopButton.setLayoutParams(stopParams);
        stopButton.setOnClickListener(v -> listener.onStopRequested());
        content.addView(stopButton);

        dialog = new AlertDialog.Builder(context)
                .setView(content)
                .setCancelable(false)
                .create();
        dialog.setCanceledOnTouchOutside(false);
    }

    public void show() { dialog.show(); }

    public void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    /** Feeds one tick of live state — elapsed recording time and the latest amplitude reading —
     *  into the timer label and the waveform. */
    public void update(int elapsedMs, int amplitude) {
        int totalSeconds = elapsedMs / 1000;
        timerText.setText(String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60));
        waveformView.addAmplitude(amplitude);
    }

    private static int dp(Context context, int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (value * density);
    }
}
