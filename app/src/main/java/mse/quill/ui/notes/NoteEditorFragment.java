package mse.quill.ui.notes;

import android.graphics.Bitmap;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import androidx.appcompat.widget.Toolbar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import mse.quill.R;
import mse.quill.data.NoteRepository;
import mse.quill.data.TagRepository;
import mse.quill.data.model.Tag;
import mse.quill.ui.notes.editor.AudioRecorder;
import mse.quill.ui.notes.editor.FormattingToolbarController;
import mse.quill.ui.notes.editor.ImageEmbedder;
import mse.quill.ui.notes.editor.KeyboardInsetsHandler;
import mse.quill.ui.notes.editor.NoteEditorView;
import mse.quill.ui.notes.editor.RecordingDialog;
import mse.quill.ui.notes.editor.model.AudioSegment;
import mse.quill.ui.notes.editor.model.ImageSegment;
import mse.quill.ui.notes.editor.model.NoteSegment;
import mse.quill.ui.notes.editor.model.TextSegment;
import mse.quill.ui.tags.TagChipView;
import mse.quill.ui.tags.TagPickerDialog;

public class NoteEditorFragment extends Fragment {

    public static final String ARG_NOTE_ID = "note_id";
    public static final String ARG_COLLECTION_ID = "collection_id";

    private EditText noteTitle;
    private FormattingToolbarController toolbarController;
    private HorizontalScrollView formattingToolbar;
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
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_note_editor, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        noteTitle = view.findViewById(R.id.note_title);
        noteEditorView = view.findViewById(R.id.note_editor_view);
        formattingToolbar = view.findViewById(R.id.formatting_toolbar);
        tagRowScroll = view.findViewById(R.id.tag_row_scroll);
        tagRowContainer = view.findViewById(R.id.tag_row_container);

        noteRepository = new NoteRepository(requireContext());
        tagRepository = new TagRepository(requireContext());

        Bundle args = getArguments();
        noteId = args != null ? args.getString(ARG_NOTE_ID) : null;
        pendingCollectionId = args != null ? args.getString(ARG_COLLECTION_ID) : null;

        noteEditorView.setContentChangeListener(this::scheduleAutoSave);
        view.findViewById(R.id.note_editor_content).setOnClickListener(v -> noteEditorView.focusEnd());
        noteTitle.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { scheduleAutoSave(); }
        });

        if (noteId != null) {
            loadExistingNote();
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
                view.findViewById(R.id.formatting_buttons),
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
                    }

                    @Override public void onHeading2Toggled() {
                        noteEditorView.applyHeadingToFocused(2);
                    }

                    @Override public void onBulletListToggled() {
                        noteEditorView.applyBulletListToFocused();
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
        toolbar.setTitle("New Note");
        toolbar.setNavigationOnClickListener(v ->
                NavHostFragment.findNavController(this).navigateUp()
        );
    }



    private void setupKeyboardBehaviour(View view) {
        KeyboardInsetsHandler.attach(view, new KeyboardInsetsHandler.KeyboardListener() {
            @Override
            public void onKeyboardShown(int height) {
                formattingToolbar.setVisibility(View.VISIBLE);
                formattingToolbar.setTranslationY(-height);
            }
            @Override
            public void onKeyboardHidden() {
                formattingToolbar.setVisibility(View.GONE);
                formattingToolbar.setTranslationY(0);
            }
        });
    }

    private void updateToolbarState() {
        toolbarController.updateState(
                noteEditorView.isBoldActive(),
                noteEditorView.isItalicActive(),
                noteEditorView.isUnderlineActive()
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
        autoSave();
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
        new android.app.AlertDialog.Builder(requireContext())
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