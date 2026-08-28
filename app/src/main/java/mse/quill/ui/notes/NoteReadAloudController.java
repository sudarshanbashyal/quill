package mse.quill.ui.notes;

import android.speech.tts.Voice;

import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

import mse.quill.R;
import mse.quill.audio.AudioPlayback;
import mse.quill.audio.ReadAloud;
import mse.quill.audio.ReadPlaylist;

/**
 * Reading a note out loud, and choosing the voice that does it.
 *
 * <p>Split out of {@code NoteEditorFragment} alongside {@link NoteExportController}, on the same
 * division: this class knows about speech, that one about files, and the fragment about editing.
 *
 * <p>The reading itself lives in {@link ReadAloud}, which is process-wide and outlives this screen
 * — a note being read carries on while the user walks around the app, which is what the mini
 * player and the watch's transport controls are for. This class is the editor's end of that: it
 * starts and stops the one reading, and answers "is there anything here worth reading".
 */
final class NoteReadAloudController {

    /** What this needs from the editor, asked for at the moment it acts. */
    interface Host {
        /** Null until the note has a row — a reading is keyed by it. */
        String noteId();

        /** What the reading is called wherever it surfaces: the mini player, the watch. */
        String noteTitle();

        /** The body only — the title is often the auto-generated "Untitled Note - &lt;date&gt;"
         *  placeholder, which shouldn't be read aloud (and, since it's never actually empty,
         *  would otherwise make a blank note look like it has something to say). */
        ReadPlaylist buildPlaylist();
    }

    private final Fragment fragment;
    private final Host host;

    NoteReadAloudController(Fragment fragment, Host host) {
        this.fragment = fragment;
        this.host = host;
    }

    /** True while <em>this</em> note is the one being read. */
    boolean isReadingThisNote() {
        return ReadAloud.isReadingNote(host.noteId());
    }

    /** Reading an empty note would just be silence, so the menu item goes away rather than
     *  misleading. A note with only a recording in it still has something to play. */
    boolean hasSomethingToRead() {
        return isReadingThisNote() || !host.buildPlaylist().isEmpty();
    }

    void toggle() {
        if (isReadingThisNote()) {
            ReadAloud.stop();
            return;
        }
        // One voice at a time: a recording the user started by hand playing under a note being
        // read aloud is just noise, and both would be fighting for the same bar. The reading
        // plays this note's own recordings itself, in the order they sit in the note.
        AudioPlayback.get(fragment.requireContext()).close();
        ReadAloud.start(fragment.requireContext(), host.noteId(), host.noteTitle(),
                host.buildPlaylist());
    }

    /** Halts a reading in progress if the last thing it had to read just got deleted out from
     *  under it. Only this note's — emptying one note is no reason to silence another. */
    void stopIfNothingLeft() {
        if (isReadingThisNote() && host.buildPlaylist().isEmpty()) ReadAloud.stop();
    }

    /** Long-press on the options button — lets the user swap out the engine's default
     *  ("robotic") voice for another one installed on the device. */
    void showVoicePicker() {
        if (!fragment.isAdded()) return;
        List<Voice> voices = ReadAloud.availableVoices(fragment.requireContext());
        if (voices.isEmpty()) return; // TTS engine not ready yet, or no voices for this locale

        Voice current = ReadAloud.currentVoice(fragment.requireContext());
        String[] labels = new String[voices.size()];
        int checkedIndex = -1;
        for (int i = 0; i < voices.size(); i++) {
            Voice voice = voices.get(i);
            labels[i] = describe(voice);
            if (current != null && voice.getName().equals(current.getName())) checkedIndex = i;
        }

        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.dialog_choose_voice_title)
                .setSingleChoiceItems(labels, checkedIndex, (dialog, which) -> {
                    ReadAloud.setVoice(fragment.requireContext(), voices.get(which));
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private String describe(Voice voice) {
        int quality;
        switch (voice.getQuality()) {
            case Voice.QUALITY_VERY_HIGH: quality = R.string.voice_quality_very_high; break;
            case Voice.QUALITY_HIGH:      quality = R.string.voice_quality_high; break;
            case Voice.QUALITY_NORMAL:    quality = R.string.voice_quality_normal; break;
            case Voice.QUALITY_LOW:       quality = R.string.voice_quality_low; break;
            default:                      quality = R.string.voice_quality_very_low;
        }
        String suffix = voice.isNetworkConnectionRequired()
                ? fragment.getString(R.string.voice_needs_internet) : "";
        return fragment.getString(R.string.voice_label,
                voice.getName(), fragment.getString(quality), suffix);
    }
}
