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
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;

import java.util.ArrayList;
import java.util.List;

import mse.quill.R;
import com.google.android.material.snackbar.Snackbar;

import mse.quill.data.AppExecutors;
import mse.quill.data.FlashcardRepository;
import mse.quill.data.NoteRepository;
import mse.quill.data.QuizRepository;
import mse.quill.data.TagRepository;
import mse.quill.data.model.Tag;
import mse.quill.ui.flashcards.FlashcardsFragment;
import mse.quill.ui.quiz.QuizDetailFragment;
import mse.quill.ui.quiz.QuizRules;
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
import mse.quill.ui.notes.editor.model.QaSegment;
import mse.quill.ui.notes.editor.model.TextSegment;
import mse.quill.ui.tags.TagChipView;
import mse.quill.ui.tags.TagPickerDialog;
import mse.quill.util.ImageExporter;
import mse.quill.util.NoteDisplayUtils;
import mse.quill.util.WindowInsetsUtils;

public class NoteEditorFragment extends Fragment implements WindowInsetsUtils.TopInsetHost {

    /** The toolbar, not the root: {@link KeyboardInsetsHandler} claims the root's insets listener,
     *  and a view only gets one — the second to attach silently replaces the first. The editor's
     *  page is the window background either way, so the status bar still matches it. */
    @Override
    public View topInsetTarget(View root) {
        return root.findViewById(R.id.toolbar);
    }

    public static final String ARG_NOTE_ID = "note_id";
    public static final String ARG_COLLECTION_ID = "collection_id";

    private static final String STATE_NOTE_ID = "state_note_id";
    private static final String STATE_COLLECTION_ID = "state_collection_id";

    private EditText noteTitle;
    private View optionsButton;
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
    private FlashcardRepository flashcardRepository;
    private QuizRepository quizRepository;
    /** Whether this note has already generated cards — decides which of the two flashcard labels
     *  the menu shows. Refreshed on resume, so deleting a deck reverts the label. */
    private boolean hasFlashcards;
    /** The same, for the quiz item: "Make quiz" until there is one, "Open quiz" after. */
    private boolean hasQuiz;
    private String noteId;
    private String pendingCollectionId;
    private boolean suppressAutoSave = false;
    /**
     * Whether the editor's fields hold the note they are supposed to. False from the moment an
     * existing note's id is known until {@link #loadExistingNote()}'s read comes back — a window
     * in which the title and body are empty <em>because nothing has been read yet</em>, not
     * because the note is empty. A note opened and left again inside that window is not a note the
     * user emptied. Always true for a new note: there is nothing to wait for.
     */
    private boolean contentLoaded = true;

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
        optionsButton = view.findViewById(R.id.note_options_button);
        noteEditorView = view.findViewById(R.id.note_editor_view);
        formattingToolbar = view.findViewById(R.id.formatting_toolbar);
        scrollView = view.findViewById(R.id.scroll_view);
        tagRowScroll = view.findViewById(R.id.tag_row_scroll);
        tagRowContainer = view.findViewById(R.id.tag_row_container);

        noteRepository = new NoteRepository(requireContext());
        tagRepository = new TagRepository(requireContext());
        flashcardRepository = new FlashcardRepository(requireContext());
        quizRepository = new QuizRepository(requireContext());

        // The note's id can come from three places, and the order matters. A note created *during*
        // this editing session has no id in the arguments — the editor was opened without one — so
        // reading the arguments first would wipe it every time the view is rebuilt. That's exactly
        // what happens on the way back from the review screen: the fragment instance survives on
        // the back stack and only its view is recreated. Left alone, it would forget which note it
        // was editing, show a blank page, and autosave itself into a *second*, empty note.
        if (noteId == null && savedInstanceState != null) {
            // The other rebuild: a genuine recreation (process death, configuration change), where
            // the fields are gone but saved state isn't.
            noteId = savedInstanceState.getString(STATE_NOTE_ID);
            pendingCollectionId = savedInstanceState.getString(STATE_COLLECTION_ID);
        }
        if (noteId == null) {
            Bundle args = getArguments();
            noteId = args != null ? args.getString(ARG_NOTE_ID) : null;
            if (pendingCollectionId == null) {
                pendingCollectionId = args != null ? args.getString(ARG_COLLECTION_ID) : null;
            }
        }

        noteReader = new NoteReader(requireContext(), new NoteReader.ReadingListener() {
            @Override public void onReadingStarted() {}
            @Override public void onReadingFinished() {}
            @Override public void onReadingFailed() {
                // TODO: show a snackbar "Could not read note aloud"
            }
        });
        optionsButton.setOnClickListener(this::showOptionsMenu);
        // Long-press still opens the voice picker, as it did on the read-aloud button this control
        // replaced — a setting for one menu item doesn't earn a line in a two-item menu.
        optionsButton.setOnLongClickListener(v -> {
            showVoicePickerDialog();
            return true;
        });

        noteEditorView.setContentChangeListener(() -> {
            scheduleAutoSave();
            stopReadingIfNothingLeft();
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
            }
        });

        if (noteId != null) {
            contentLoaded = false;
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

                    @Override public void onQaBlockRequested() {
                        noteEditorView.insertQaBlockAfterFocused();
                        updateToolbarState();
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
        toolbarController.updateState(noteEditorView.getFormattingState());
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
            contentLoaded = true;
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
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Carries the in-session note id across the fragment being torn down and rebuilt from the
        // back stack — see the restore in onViewCreated.
        outState.putString(STATE_NOTE_ID, noteId);
        outState.putString(STATE_COLLECTION_ID, pendingCollectionId);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStudyState();
    }

    /** Asked on every resume rather than cached from load, so coming back from the review or quiz
     *  screen having deleted what was there puts the menu's "make it" labels back. */
    private void refreshStudyState() {
        if (noteId == null) {
            hasFlashcards = false;
            hasQuiz = false;
            return;
        }
        flashcardRepository.countForNote(noteId, count -> {
            if (isAdded()) hasFlashcards = count > 0;
        });
        quizRepository.existsForNote(noteId, exists -> {
            if (isAdded()) hasQuiz = exists;
        });
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
        autoSave();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        noteReader.shutdown();
    }

    /**
     * The note's whole-document actions. Kept behind one control rather than a row of buttons: the
     * set will keep growing (quizzes, export), and none of it is used often enough to earn
     * permanent space beside the title.
     */
    private void showOptionsMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.inflate(R.menu.menu_note_options);

        menu.getMenu().findItem(R.id.action_turn_into_flashcards).setTitle(
                hasFlashcards ? R.string.action_review_flashcards
                              : R.string.action_turn_into_flashcards);
        menu.getMenu().findItem(R.id.action_make_quiz).setTitle(
                hasQuiz ? R.string.action_open_quiz : R.string.action_make_quiz);

        MenuItem playAloud = menu.getMenu().findItem(R.id.action_play_aloud);
        boolean speaking = noteReader.isSpeaking();
        playAloud.setTitle(speaking ? R.string.action_stop_reading : R.string.action_read_aloud);
        playAloud.setIcon(speaking ? R.drawable.ic_menu_pause : R.drawable.ic_menu_play);
        // Reading an empty note would just be silence, so the item goes away rather than misleading.
        playAloud.setVisible(speaking || !buildSpokenText().isEmpty());

        menu.setForceShowIcon(true);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_play_aloud) {
                toggleReadAloud();
                return true;
            }
            if (id == R.id.action_turn_into_flashcards) {
                openFlashcards();
                return true;
            }
            if (id == R.id.action_make_quiz) {
                openQuiz();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void toggleReadAloud() {
        if (noteReader.isSpeaking()) {
            noteReader.stop();
        } else {
            noteReader.speak(buildSpokenText());
        }
    }

    /**
     * Opens the review screen for this note's Q&amp;A blocks.
     *
     * <p>Only blocks with <em>both</em> halves filled in become cards — a question with no answer
     * has nothing to turn over — so a note can hold Q&amp;A and still have no deck, and that's the
     * case the message covers. The save is forced through first because the review screen reads the
     * note back from storage: it needs the blocks' ids, and for a brand-new note, a row to read at
     * all.
     */
    private void openFlashcards() {
        List<NoteSegment> segments = noteEditorView.exportSegments();
        // A note whose blocks have all been emptied still has its old cards, so the deck is worth
        // opening even when there's nothing left to generate from.
        if (!hasFlashcards && FlashcardRepository.reviewableQa(segments).isEmpty()) {
            Snackbar.make(requireView(), R.string.flashcards_no_qa_message, Snackbar.LENGTH_LONG)
                    .show();
            return;
        }

        noteReader.stop(); // the review screen is a different mode; don't leave a voice running
        saveNow(() -> {
            if (!isAdded() || noteId == null) return;
            Bundle args = new Bundle();
            args.putString(FlashcardsFragment.ARG_NOTE_ID, noteId);
            NavHostFragment.findNavController(this).navigate(R.id.flashcardsFragment, args);
        });
    }

    /**
     * Opens this note's quiz, making it on the first run.
     *
     * <p>The five-block minimum is a property of how questions are built, not a rule for its own
     * sake: every wrong option is another block's answer, so a note with four of them can only ever
     * offer the same three distractors and the quiz becomes a memory game about the note's layout.
     * A note that already has a quiz opens it regardless — that screen can explain a shortfall
     * better than a Snackbar on the way out can.
     */
    private void openQuiz() {
        List<NoteSegment> segments = noteEditorView.exportSegments();
        int usable = FlashcardRepository.reviewableQa(segments).size();
        if (!hasQuiz && usable < QuizRules.MIN_QA_BLOCKS) {
            Snackbar.make(requireView(),
                            getString(R.string.quiz_not_enough_qa_message, QuizRules.MIN_QA_BLOCKS),
                            Snackbar.LENGTH_LONG)
                    .show();
            return;
        }

        noteReader.stop(); // a quiz is a different mode; don't leave a voice running under it
        saveNow(() -> {
            if (!isAdded() || noteId == null) return;
            quizRepository.ensureForNote(noteId, quiz -> {
                if (!isAdded()) return;
                hasQuiz = true;
                Bundle args = new Bundle();
                args.putString(QuizDetailFragment.ARG_QUIZ_ID, quiz.id);
                NavHostFragment.findNavController(this).navigate(R.id.quizDetailFragment, args);
            });
        });
    }

    /** Body text only — the title is often just the auto-generated "Untitled Note - <date>"
     *  placeholder, which shouldn't be read aloud (and, since it's never actually empty, would
     *  otherwise make a blank note look like it has something to say). */
    private String buildSpokenText() {
        return noteEditorView.getPlainText().trim();
    }

    /** Halts a reading in progress if its last bit of text just got deleted out from under it. */
    private void stopReadingIfNothingLeft() {
        if (noteReader.isSpeaking() && buildSpokenText().isEmpty()) {
            noteReader.stop();
        }
    }

    /** Long-press on the options button — lets the user swap out the engine's default
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
        autoSave(null);
    }

    /**
     * Flushes the pending debounce and saves right now, calling back once the note is on disk.
     *
     * <p>Used by anything that has to hand the note off to another screen: they read it back by id,
     * so waiting for the 500ms debounce would show them the note as it was before the last edit —
     * or, for a note that has never been saved, no note at all.
     */
    private void saveNow(Runnable onSaved) {
        if (saveRunnable != null) {
            handler.removeCallbacks(saveRunnable);
            saveRunnable = null;
        }
        autoSave(onSaved);
    }

    /** @param onSaved run on the main thread once the write lands; skipped if there was nothing
     *                worth writing (a blank note is never created, and an emptied one is deleted). */
    private void autoSave(Runnable onSaved) {
        // An existing note whose read hasn't come back yet has empty fields that mean "not loaded",
        // not "emptied" — saving would blank it and the !hasContent branch below would delete it
        // outright. Opening a note and leaving again before it painted did exactly that.
        if (!contentLoaded) return;

        String title = noteTitle.getText().toString().trim();
        List<NoteSegment> segments = noteEditorView.exportSegments();
        boolean hasContent = !title.isEmpty() || hasRealContent(segments);

        if (noteId == null) {
            if (!hasContent) return; // blank note — don't create a row for it

            // The id is minted here, on the main thread, so noteId is usable by every later save
            // immediately — those saves then queue behind this insert on the repository's single
            // disk thread and land in order. Waiting on a created-id callback instead meant a save
            // arriving mid-creation had no id to write to and was dropped on the floor, taking
            // everything typed since creation started with it. Leaving a new note quickly — a back
            // gesture a moment after the first keystroke — is exactly that race.
            noteId = NoteRepository.newNoteId();
            noteRepository.createNote(noteId, title, pendingCollectionId, () -> {
                if (isAdded()) renderTagRow();
            });
        } else if (!hasContent) {
            noteRepository.deleteNote(noteId, null);
            return;
        }

        noteRepository.saveNote(noteId, title, segments, onSaved);
    }

    private boolean hasRealContent(List<NoteSegment> segments) {
        for (NoteSegment segment : segments) {
            if (segment instanceof ImageSegment) return true;
            if (segment instanceof AudioSegment) return true;
            // A note whose only content is a Q&A block is a note with content — it used to survive
            // solely because the auto-generated title is never empty.
            if (segment instanceof QaSegment) return true;
            if (segment instanceof TextSegment
                    && !((TextSegment) segment).content.toString().trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}