package mse.quill.ui.notes;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import android.os.Handler;
import android.os.Looper;
import android.speech.tts.Voice;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.appcompat.widget.Toolbar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import mse.quill.R;
import com.google.android.material.snackbar.Snackbar;

import mse.quill.data.AppExecutors;
import mse.quill.data.NoteRepository;
import mse.quill.data.TagRepository;
import mse.quill.data.model.Tag;
import mse.quill.ui.notes.editor.AudioRecorder;
import mse.quill.ui.notes.editor.FormattingToolbarController;
import mse.quill.ui.notes.editor.ImageEmbedder;
import mse.quill.ui.notes.editor.KeyboardInsetsHandler;
import mse.quill.ui.notes.editor.NoteEditorView;
import mse.quill.ui.notes.editor.NoteReader;
import mse.quill.ui.notes.editor.RecordingDialog;
import mse.quill.ui.notes.editor.segment.BaseSegmentView;
import mse.quill.ui.notes.editor.model.AudioSegment;
import mse.quill.ui.notes.editor.model.ImageSegment;
import mse.quill.ui.notes.editor.model.NoteSegment;
import mse.quill.ui.notes.editor.model.TextSegment;
import mse.quill.ui.tags.TagChipView;
import mse.quill.ui.tags.TagPickerDialog;
import mse.quill.util.ImageExporter;
import mse.quill.util.NoteDisplayUtils;

public class NoteEditorFragment extends Fragment {

    public static final String ARG_NOTE_ID = "note_id";
    public static final String ARG_COLLECTION_ID = "collection_id";

    private EditText noteTitle;
    private Button readAloudButton;
    private NoteReader noteReader;
    private FormattingToolbarController toolbarController;
    private LinearLayout formattingToolbar;
    private ScrollView scrollView;
    private ActivityResultLauncher<String> storagePermissionLauncher;
    private String pendingExportPath;
    private BaseSegmentView.ExportResult pendingExportResult;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable saveRunnable;

    private NoteEditorView noteEditorView;

    private ImageEmbedder imageEmbedder;
    private AudioRecorder audioRecorder;
    private RecordingDialog recordingDialog;
    private Runnable recordingTickRunnable;

    private NoteRepository noteRepository;
    private TagRepository tagRepository;
    private String noteId;
    private String pendingCollectionId;
    private final AtomicBoolean isCreatingNote = new AtomicBoolean(false);
    private boolean suppressAutoSave = false;

    private View tagRowScroll;
    private LinearLayout tagRowContainer;
    private List<Tag> currentTags = new ArrayList<>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Must be registered before the fragment reaches STARTED, so it can't live in
        // onViewCreated alongside the listener that uses it.
        storagePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        runExport();
                    } else {
                        abandonExport();
                    }
                });
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_note_editor, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        noteTitle = view.findViewById(R.id.note_title);
        readAloudButton = view.findViewById(R.id.read_aloud_button);
        noteEditorView = view.findViewById(R.id.note_editor_view);
        formattingToolbar = view.findViewById(R.id.formatting_toolbar);
        scrollView = view.findViewById(R.id.scroll_view);
        tagRowScroll = view.findViewById(R.id.tag_row_scroll);
        tagRowContainer = view.findViewById(R.id.tag_row_container);

        noteRepository = new NoteRepository(requireContext());
        tagRepository = new TagRepository(requireContext());

        Bundle args = getArguments();
        noteId = args != null ? args.getString(ARG_NOTE_ID) : null;
        pendingCollectionId = args != null ? args.getString(ARG_COLLECTION_ID) : null;

        noteReader = new NoteReader(requireContext(), new NoteReader.ReadingListener() {
            @Override public void onReadingStarted() {
                readAloudButton.setText(R.string.action_stop_reading_glyph);
            }
            @Override public void onReadingFinished() {
                readAloudButton.setText(R.string.action_read_aloud_glyph);
            }
            @Override public void onReadingFailed() {
                readAloudButton.setText(R.string.action_read_aloud_glyph);
                // TODO: show a snackbar "Could not read note aloud"
            }
        });
        readAloudButton.setOnClickListener(v -> {
            if (noteReader.isSpeaking()) {
                noteReader.stop();
                readAloudButton.setText(R.string.action_read_aloud_glyph);
            } else {
                noteReader.speak(buildSpokenText());
            }
        });
        readAloudButton.setOnLongClickListener(v -> {
            showVoicePickerDialog();
            return true;
        });

        noteEditorView.setContentChangeListener(() -> {
            scheduleAutoSave();
            updateReadAloudVisibility();
            updateToolbarState();
        });
        // Heading/bullet markers describe the caret's line, so they have to follow the caret and
        // not just edits — otherwise tapping from a heading into body text leaves H1 lit.
        noteEditorView.setSelectionChangeListener(this::updateToolbarState);
        noteEditorView.setMediaExportListener(this::exportMedia);
        view.findViewById(R.id.note_editor_content).setOnClickListener(v -> noteEditorView.focusEnd());
        noteTitle.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                scheduleAutoSave();
                updateReadAloudVisibility();
            }
        });

        if (noteId != null) {
            loadExistingNote();
        } else {
            // Pre-filled default title, same format used elsewhere for untitled notes — also
            // what makes the note (and its "add tag" option) exist immediately rather than only
            // once the user has typed something themselves.
            noteTitle.setText(NoteDisplayUtils.untitledWithDate(requireContext(), System.currentTimeMillis()));
        }

        imageEmbedder = new ImageEmbedder(this, new ImageEmbedder.ImageResultListener() {
            @Override
            public void onImageReady(Bitmap bitmap, String filePath) {
                noteEditorView.insertImageAfterFocused(filePath);
            }

            @Override
            public void onImageFailed() {
                // TODO: show a snackbar "Could not load image"
            }
        });

        audioRecorder = new AudioRecorder(this, new AudioRecorder.RecordingListener() {
            @Override
            public void onRecordingStarted() {
                toolbarController.setRecordingState(true);
                recordingDialog = new RecordingDialog(requireContext(), () -> audioRecorder.toggleRecording());
                recordingDialog.show();
                startRecordingTicker();
            }

            @Override
            public void onRecordingFinished(String filePath, int durationMs) {
                toolbarController.setRecordingState(false);
                dismissRecordingDialog();
                noteEditorView.insertAudioAfterFocused(filePath, durationMs);
            }

            @Override
            public void onRecordingFailed() {
                toolbarController.setRecordingState(false);
                dismissRecordingDialog();
                // TODO: show a snackbar "Could not record audio"
            }

            @Override
            public void onPermissionDenied() {
                toolbarController.setRecordingState(false);
                // TODO: show a snackbar "Microphone permission is required to record audio"
            }
        });

        toolbarController = new FormattingToolbarController(
                formattingToolbar,
                new FormattingToolbarController.FormatListener() {
                    @Override public void onBoldToggled() {
                        noteEditorView.applyBoldToFocused();
                        updateToolbarState();
                    }
                    @Override public void onItalicToggled() {
                        noteEditorView.applyItalicToFocused();
                        updateToolbarState();
                    }
                    @Override public void onUnderlineToggled() {
                        noteEditorView.applyUnderlineToFocused();
                        updateToolbarState();
                    }

                    @Override public void onHeading1Toggled() {
                        noteEditorView.applyHeadingToFocused(1);
                        updateToolbarState();
                    }

                    @Override public void onHeading2Toggled() {
                        noteEditorView.applyHeadingToFocused(2);
                        updateToolbarState();
                    }

                    @Override public void onBulletListToggled() {
                        noteEditorView.applyBulletListToFocused();
                        updateToolbarState();
                    }

                    @Override
                    public void onImageRequested() {
                        showImageSourceDialog();
                    }

                    @Override
                    public void onAudioRequested() {
                        audioRecorder.toggleRecording();
                    }
                }
        );

        setupToolbar(view);
        setupKeyboardBehaviour(view);
    }

    private void setupToolbar(View view) {
        Toolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v ->
                NavHostFragment.findNavController(this).navigateUp()
        );
    }



    private void setupKeyboardBehaviour(View view) {
        KeyboardInsetsHandler.attach(view, new KeyboardInsetsHandler.KeyboardListener() {
            @Override
            public void onKeyboardShown(int height) {
                formattingToolbar.setVisibility(View.VISIBLE);
                reserveKeyboardSpace(view, height);
            }
            @Override
            public void onKeyboardHidden() {
                formattingToolbar.setVisibility(View.GONE);
                reserveKeyboardSpace(view, 0);
            }
        });
    }

    /**
     * Shrinks the editor by the keyboard's height instead of sliding views up over it.
     *
     * <p>The distinction matters. {@code targetSdk} is 35+, so the system enforces edge-to-edge and
     * the window never resizes for the IME — the editor has to reserve that space itself. Doing it
     * with {@code translationY} on the toolbar (the previous approach) only moves pixels: the
     * ScrollView, constrained above the toolbar, keeps its full-height layout bounds and its
     * viewport still nominally extends behind the keyboard. Android's own "reveal the focused
     * view" pass then finds a tapped segment already inside those bounds and correctly decides no
     * scrolling is needed — which is exactly why segments near the end of a note were never
     * revealed. Padding is a layout change, so the toolbar lands above the keyboard by its
     * existing constraint and the ScrollView's viewport becomes truthful.
     */
    private void reserveKeyboardSpace(View root, int height) {
        if (root.getPaddingBottom() == height) return;
        root.setPadding(root.getPaddingLeft(), root.getPaddingTop(), root.getPaddingRight(), height);
        revealFocusedInput();
    }

    /**
     * Re-runs the reveal after the resize. The tap that focused an input happened while the
     * keyboard was still down — at which point there was genuinely nothing to scroll past — and
     * nothing re-triggers it once the space is reserved. With the window not resizing, {@code
     * ViewRootImpl}'s own keep-focus-visible pass never runs either, so this is the one nudge that
     * has to be explicit.
     */
    private void revealFocusedInput() {
        scrollView.post(() -> {
            View focused = scrollView.findFocus();
            if (focused == null) return;
            Rect caret = new Rect();
            focused.getFocusedRect(caret); // for an EditText this is the cursor line, not the whole field
            focused.requestRectangleOnScreen(caret, true);
        });
    }

    /**
     * Copies an embedded file out to the shared Pictures collection. Below API 29 that needs
     * {@code WRITE_EXTERNAL_STORAGE}, so the request is made here — a segment view has no way to
     * ask for a runtime permission.
     */
    private void exportMedia(String filePath, BaseSegmentView.ExportResult result) {
        pendingExportPath = filePath;
        pendingExportResult = result;
        if (ImageExporter.requiresStoragePermission()
                && ContextCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        runExport();
    }

    private void runExport() {
        String path = pendingExportPath;
        BaseSegmentView.ExportResult result = pendingExportResult;
        pendingExportPath = null;
        pendingExportResult = null;
        if (path == null || result == null) return;

        AppExecutors executors = AppExecutors.getInstance();
        executors.diskIO(() -> {
            boolean saved = ImageExporter.saveToPictures(requireContext().getApplicationContext(), path);
            executors.mainThread(() -> result.onExportFinished(saved));
        });
    }

    private void abandonExport() {
        BaseSegmentView.ExportResult result = pendingExportResult;
        pendingExportPath = null;
        pendingExportResult = null;
        if (result != null) result.onExportFinished(false);
    }

    private void updateToolbarState() {
        toolbarController.updateState(
                noteEditorView.isBoldActive(),
                noteEditorView.isItalicActive(),
                noteEditorView.isUnderlineActive(),
                noteEditorView.getActiveHeadingLevel(),
                noteEditorView.isBulletListActive()
        );
    }

    private void loadExistingNote() {
        noteRepository.loadNote(noteId, (note, segments) -> {
            if (!isAdded()) return;
            suppressAutoSave = true;
            if (note != null) {
                noteTitle.setText(note.title);
                pendingCollectionId = note.collectionId;
            }
            noteEditorView.loadSegments(segments);
            suppressAutoSave = false;
            updateReadAloudVisibility();
        });
        tagRepository.loadTagsForNote(noteId, tags -> {
            if (!isAdded()) return;
            currentTags = tags;
            renderTagRow();
        });
    }

    private void renderTagRow() {
        if (noteId == null) {
            tagRowScroll.setVisibility(View.GONE);
            return;
        }
        tagRowScroll.setVisibility(View.VISIBLE);
        tagRowContainer.removeAllViews();

        for (Tag tag : currentTags) {
            View chip = TagChipView.buildChip(requireContext(), tag);
            chip.setOnClickListener(v -> openTagPicker());
            tagRowContainer.addView(chip);
        }

        View addChip = TagChipView.buildAddChip(requireContext());
        addChip.setOnClickListener(v -> openTagPicker());
        tagRowContainer.addView(addChip);
    }

    private void openTagPicker() {
        tagRepository.loadAllTags(allTags ->
                TagPickerDialog.show(requireContext(), tagRepository, allTags, currentTags, tagIds ->
                        tagRepository.setNoteTags(noteId, tagIds, () ->
                                tagRepository.loadTagsForNote(noteId, tags -> {
                                    if (!isAdded()) return;
                                    currentTags = tags;
                                    renderTagRow();
                                }))));
    }

    private void scheduleAutoSave() {
        if (suppressAutoSave) return;
        if (saveRunnable != null) handler.removeCallbacks(saveRunnable);
        saveRunnable = this::autoSave;
        handler.postDelayed(saveRunnable, 500);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (saveRunnable != null) {
            handler.removeCallbacks(saveRunnable);
            saveRunnable = null;
        }
        audioRecorder.cancelIfRecording();
        dismissRecordingDialog();
        toolbarController.setRecordingState(false);
        noteEditorView.stopAllAudioPlayback();
        noteReader.stop();
        readAloudButton.setText(R.string.action_read_aloud_glyph);
        autoSave();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        noteReader.shutdown();
    }

    /** Body text only — the title is often just the auto-generated "Untitled Note - <date>"
     *  placeholder, which shouldn't be read aloud (and, since it's never actually empty, would
     *  otherwise make the read-aloud button appear "has content" even for a genuinely blank note). */
    private String buildSpokenText() {
        return noteEditorView.getPlainText().trim();
    }

    /** The read-aloud button only makes sense when there's text to read — hides it otherwise,
     *  and halts a reading in progress if its last bit of text just got deleted out from under it. */
    private void updateReadAloudVisibility() {
        boolean hasText = !buildSpokenText().isEmpty();
        if (!hasText && noteReader.isSpeaking()) {
            noteReader.stop();
        }
        readAloudButton.setVisibility(hasText ? View.VISIBLE : View.GONE);
    }

    /** Long-press on the read-aloud button — lets the user swap out the engine's default
     *  ("robotic") voice for another one installed on the device. */
    private void showVoicePickerDialog() {
        List<Voice> voices = noteReader.getAvailableVoices();
        if (voices.isEmpty()) return; // TTS engine not ready yet, or no voices for this locale

        Voice current = noteReader.getCurrentVoice();
        String[] labels = new String[voices.size()];
        int checkedIndex = -1;
        for (int i = 0; i < voices.size(); i++) {
            Voice voice = voices.get(i);
            labels[i] = describeVoice(voice);
            if (current != null && voice.getName().equals(current.getName())) checkedIndex = i;
        }

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_choose_voice_title)
                .setSingleChoiceItems(labels, checkedIndex, (dialog, which) -> {
                    noteReader.setVoice(voices.get(which));
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private String describeVoice(Voice voice) {
        String quality;
        switch (voice.getQuality()) {
            case Voice.QUALITY_VERY_HIGH: quality = "Very high"; break;
            case Voice.QUALITY_HIGH: quality = "High"; break;
            case Voice.QUALITY_NORMAL: quality = "Normal"; break;
            case Voice.QUALITY_LOW: quality = "Low"; break;
            default: quality = "Very low";
        }
        String suffix = voice.isNetworkConnectionRequired() ? " · needs internet" : "";
        return voice.getName() + " (" + quality + suffix + ")";
    }

    /** Polls the in-progress recording's elapsed time and amplitude a few times a second to
     *  drive the popup's timer and waveform while it's showing. */
    private void startRecordingTicker() {
        recordingTickRunnable = new Runnable() {
            @Override
            public void run() {
                if (recordingDialog == null) return;
                recordingDialog.update(audioRecorder.getElapsedMs(), audioRecorder.getMaxAmplitude());
                handler.postDelayed(this, 100);
            }
        };
        handler.post(recordingTickRunnable);
    }

    private void stopRecordingTicker() {
        if (recordingTickRunnable != null) {
            handler.removeCallbacks(recordingTickRunnable);
            recordingTickRunnable = null;
        }
    }

    private void dismissRecordingDialog() {
        stopRecordingTicker();
        if (recordingDialog != null) {
            recordingDialog.dismiss();
            recordingDialog = null;
        }
    }

    private void showImageSourceDialog() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Insert image")
                .setItems(new String[]{"Take photo", "Choose from gallery"}, (dialog, which) -> {
                    if (which == 0) imageEmbedder.openCamera();
                    else imageEmbedder.openGallery();
                })
                .show();
    }

    private void autoSave() {
        String title = noteTitle.getText().toString().trim();
        List<NoteSegment> segments = noteEditorView.exportSegments();
        boolean hasContent = !title.isEmpty() || hasRealContent(segments);

        if (noteId == null) {
            if (!hasContent) return; // blank note — don't create a row for it
            if (!isCreatingNote.compareAndSet(false, true)) return; // creation already in flight

            String collectionForCreation = pendingCollectionId;
            noteRepository.createNote(title, collectionForCreation, createdId -> {
                noteId = createdId;
                isCreatingNote.set(false);
                noteRepository.saveNote(noteId, title, segments, null);
                if (isAdded()) renderTagRow();
            });
            return;
        }

        if (!hasContent) {
            noteRepository.deleteNote(noteId, null);
            return;
        }

        noteRepository.saveNote(noteId, title, segments, null);
    }

    private boolean hasRealContent(List<NoteSegment> segments) {
        for (NoteSegment segment : segments) {
            if (segment instanceof ImageSegment) return true;
            if (segment instanceof AudioSegment) return true;
            if (segment instanceof TextSegment
                    && !((TextSegment) segment).content.toString().trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}