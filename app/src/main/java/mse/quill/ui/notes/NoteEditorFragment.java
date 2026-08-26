package mse.quill.ui.notes;

import android.Manifest;
import android.content.Context;
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
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.appcompat.widget.PopupMenu;

import java.util.ArrayList;
import java.util.List;

import mse.quill.R;
import com.google.android.material.snackbar.Snackbar;

import mse.quill.audio.AudioPlayback;
import mse.quill.audio.ReadAloud;
import mse.quill.audio.ReadPlaylist;
import mse.quill.data.CollectionRepository;
import mse.quill.data.FlashcardStore;
import mse.quill.data.Repositories;
import mse.quill.data.NoteStore;
import mse.quill.data.Repositories;
import mse.quill.security.CollectionLock;
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
import mse.quill.ui.notes.editor.RecordingDialog;
import mse.quill.data.model.AudioSegment;
import mse.quill.data.model.ImageSegment;
import mse.quill.data.model.NoteSegment;
import mse.quill.data.model.QaSegment;
import mse.quill.data.model.WhiteboardSegment;
import mse.quill.data.model.TextSegment;
import mse.quill.ui.tags.TagChipView;
import mse.quill.ui.tags.TagPickerDialog;
import mse.quill.util.NoteDisplayUtils;
import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import mse.quill.data.WhiteboardRepository;
import mse.quill.ui.whiteboard.WhiteboardFragment;
import mse.quill.ui.whiteboard.WhiteboardPickerDialog;
import mse.quill.ui.whiteboard.WhiteboardPreferences;
import mse.quill.util.WindowInsetsUtils;

public class NoteEditorFragment extends Fragment
        implements WindowInsetsUtils.TopInsetHost, NoteExportController.Host {

    /** The header, not the root: {@link KeyboardInsetsHandler} claims the root's insets listener,
     *  and a view only gets one — the second to attach silently replaces the first. The editor's
     *  page is the window background either way, so the status bar still matches it. */
    @Override
    public View topInsetTarget(View root) {
        return root.findViewById(R.id.header);
    }

    public static final String ARG_NOTE_ID = "note_id";
    public static final String ARG_COLLECTION_ID = "collection_id";

    private static final String STATE_NOTE_ID = "state_note_id";
    private static final String STATE_COLLECTION_ID = "state_collection_id";

    private EditText noteTitle;
    private View optionsButton;
    private FormattingToolbarController toolbarController;
    private LinearLayout formattingToolbar;
    private ScrollView scrollView;
    private ActivityResultLauncher<String> storagePermissionLauncher;
    /** What to resume once {@code storagePermissionLauncher} comes back — see
     *  {@link #requestStoragePermission}. */
    private Runnable pendingStorageGranted;
    private Runnable pendingStorageDenied;
    /** Every way this note leaves Quill — see {@link NoteExportController}. */
    private NoteExportController export;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable saveRunnable;

    private NoteEditorView noteEditorView;
    private WhiteboardRepository whiteboardRepository;

    private ImageEmbedder imageEmbedder;
    private AudioRecorder audioRecorder;
    private RecordingDialog recordingDialog;
    private Runnable recordingTickRunnable;

    private NoteStore noteRepository ;
    private TagRepository tagRepository;
    private CollectionRepository collectionRepository;
    private FlashcardStore flashcardRepository ;
    private QuizRepository quizRepository;
    /** Whether this note has already generated cards — decides which of the two flashcard labels
     *  the menu shows. Refreshed on resume, so deleting a deck reverts the label. */
    private boolean hasFlashcards;
    /** The same, for the quiz item: "Make quiz" until there is one, "Open quiz" after. */
    private boolean hasQuiz;
    private String noteId;
    private String pendingCollectionId;
    /** The note's own creation date, so a bundle can carry it — an imported copy inherits when the
     *  note was written, which is a fact about it, rather than pretending it was written today. */
    private long noteCreatedAt;
    /** Whether this note's collection is locked. Read when the note loads so the options menu can
     *  answer synchronously; see {@link CollectionRepository#isLocked}. */
    private boolean collectionLocked;
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
                    // Two things queue behind this permission — saving an image out of a segment,
                    // and exporting the whole note — and they want different things on a refusal,
                    // so both outcomes are held rather than assumed.
                    Runnable onGranted = pendingStorageGranted;
                    Runnable onDenied = pendingStorageDenied;
                    pendingStorageGranted = null;
                    pendingStorageDenied = null;
                    if (granted) {
                        if (onGranted != null) onGranted.run();
                    } else if (onDenied != null) {
                        onDenied.run();
                    }
                });

        export = new NoteExportController(this, this);
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

        noteRepository = Repositories.notes(requireContext());
        tagRepository = new TagRepository(requireContext());
        collectionRepository = new CollectionRepository(requireContext());
        flashcardRepository = Repositories.flashcards(requireContext());
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

        // The voice itself belongs to ReadAloud, not to this screen — a reading carries on when you
        // leave the note, controlled from the now-playing bar. Bind the engine now so a long-press
        // on the options button has voices to offer before anything has been spoken.
        ReadAloud.warmUp(requireContext());
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
        noteEditorView.setMediaExportListener(export::exportMedia);
        noteEditorView.setWhiteboardOpenListener(this::openWhiteboard);
        view.findViewById(R.id.note_editor_content).setOnClickListener(v -> noteEditorView.focusEnd());
        // The title sits in the header now, so the next focusable after it is the formatting
        // toolbar — pressing "next" landed on the italic button. Send the caret where the key
        // means to send it.
        noteTitle.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_NEXT) return false;
            noteEditorView.focusBodyStart();
            return true;
        });
        noteTitle.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                scheduleAutoSave();
                // Renaming the note renames its recordings, including one already playing, and the
                // reading if this note is the one being read.
                noteEditorView.setAudioClipTitle(clipTitle());
                if (ReadAloud.isReadingNote(noteId)) ReadAloud.retitle(clipTitle());
            }
        });

        if (noteId != null) {
            contentLoaded = false;
            loadExistingNote();
        } else {
            // The default name is a hint, not text. It used to be typed into the field for real,
            // which meant naming a note began with selecting a sentence and deleting it — the one
            // moment the user definitely wants to just type. Left untouched it stays empty, and
            // every list resolves an empty title to this same string via NoteDisplayUtils.
            showUntitledHint(System.currentTimeMillis());
        }

        imageEmbedder = new ImageEmbedder(this, new ImageEmbedder.ImageResultListener() {
            @Override
            public void onImageReady(Bitmap bitmap, String filePath) {
                noteEditorView.insertImageAfterFocused(filePath);
            }

            @Override
            public void onImageFailed() {
                Snackbar.make(requireView(), R.string.image_load_failed, Snackbar.LENGTH_LONG).show();
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
                noteEditorView.setAudioClipTitle(clipTitle());
            }

            @Override
            public void onRecordingFailed() {
                toolbarController.setRecordingState(false);
                dismissRecordingDialog();
                Snackbar.make(requireView(), R.string.record_audio_failed, Snackbar.LENGTH_LONG).show();
            }

            @Override
            public void onPermissionDenied() {
                toolbarController.setRecordingState(false);
                Snackbar.make(requireView(), R.string.microphone_permission_required, Snackbar.LENGTH_LONG).show();
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
                        insertQaBlock();
                    }

                    @Override public void onWhiteboardRequested() {
                        showWhiteboardSourceDialog();
                    }

                    /** Only headings reach this: everything else in the bar works everywhere the
                     *  caret can go. Named from the control's own label so the sentence can't drift
                     *  from the button that produced it. */
                    @Override public void onUnavailableRequested(
                            FormattingToolbarController.Item item) {
                        Snackbar.make(requireView(),
                                        getString(R.string.formatting_unavailable_in_qa,
                                                getString(item.descriptionRes)),
                                        Snackbar.LENGTH_SHORT)
                                .show();
                    }
                }
        );

        setupHeader(view);
        setupKeyboardBehaviour(view);
    }

    /**
     * New board or an existing one — the same two ways you'd attach a photo.
     *
     * <p>A new board is created attached to this note and opened straight away: you asked for a
     * board because you want to draw on it, and the embed is already in the note when you come
     * back. Importing only points at a board that already exists, so it stays in the note.
     */
    private void showWhiteboardSourceDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setItems(new CharSequence[]{
                        getString(R.string.whiteboard_new),
                        getString(R.string.whiteboard_import)
                }, (dialog, which) -> {
                    if (which == 0) createAndAttachWhiteboard();
                    else showWhiteboardPicker();
                })
                .show();
    }

    private void createAndAttachWhiteboard() {
        // Saved first: navigating away is what would otherwise persist the note, and the embed has
        // to be in the document by then or coming back would show a note without it.
        whiteboardRepository().createWhiteboard(null, noteId,
                WhiteboardPreferences.defaultBackground(requireContext()), whiteboardId -> {
                    if (!isAdded()) return;
                    noteEditorView.insertWhiteboardAfterFocused(whiteboardId);
                    updateToolbarState();
                    autoSave();
                    openWhiteboard(whiteboardId);
                });
    }

    private void showWhiteboardPicker() {
        whiteboardRepository().loadWhiteboards(whiteboards -> {
            if (!isAdded()) return;
            if (whiteboards.isEmpty()) {
                Toast.makeText(requireContext(), R.string.whiteboard_import_empty,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            WhiteboardPickerDialog.show(requireContext(), whiteboards, board -> {
                noteEditorView.insertWhiteboardAfterFocused(board.id);
                updateToolbarState();
            });
        });
    }

    private void openWhiteboard(String whiteboardId) {
        if (!isAdded()) return;
        Bundle args = new Bundle();
        args.putString(WhiteboardFragment.ARG_WHITEBOARD_ID, whiteboardId);
        args.putString(WhiteboardFragment.ARG_NOTE_ID, noteId);
        NavHostFragment.findNavController(this).navigate(R.id.whiteboardFragment, args);
    }

    private WhiteboardRepository whiteboardRepository() {
        if (whiteboardRepository == null) {
            whiteboardRepository = new WhiteboardRepository(requireContext());
        }
        return whiteboardRepository;
    }

    private void setupHeader(View view) {
        view.findViewById(R.id.back_button).setOnClickListener(v ->
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

    // ── NoteExportController.Host ────────────────────────────────────────────
    //
    // What an export needs to know about the note, asked for at the moment it happens so there is
    // no second copy of the note's state to go stale.

    @Override
    public List<NoteSegment> segmentsForExport() {
        return noteEditorView.exportSegments();
    }

    @Override
    public String titleForExport() {
        return clipTitle();
    }

    @Override
    public List<Tag> tagsForExport() {
        return currentTags;
    }

    @Override
    public long createdAtForExport() {
        return noteCreatedAt;
    }

    @Override
    public boolean isCollectionLocked() {
        return collectionLocked;
    }

    /**
     * Requests {@code WRITE_EXTERNAL_STORAGE} if this device still needs it, remembering both
     * outcomes. The launcher has to be registered before the fragment reaches STARTED, which is
     * the only reason this lives here rather than in the controller.
     */
    @Override
    public void requestStoragePermission(Runnable onGranted, Runnable onDenied) {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            onGranted.run();
            return;
        }
        pendingStorageGranted = onGranted;
        pendingStorageDenied = onDenied;
        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private void updateToolbarState() {
        toolbarController.updateState(noteEditorView.getFormattingState());
    }

    private void loadExistingNote() {
        noteRepository.loadNote(noteId, (note, segments) -> {
            if (!isAdded()) return;

            // This method only ever runs for a real, existing note id (see onViewCreated: a brand
            // new note skips it entirely). So a null note here is never "blank, start typing" —
            // it means the row exists but couldn't be read right now, almost always because its
            // collection re-locked (or was never unlocked this session) between the tap that
            // opened this screen and this callback landing. contentLoaded is deliberately left
            // false in that case: autoSave()'s very first line refuses to run without it, which is
            // what stops the empty-looking editor from being "helpfully" autosaved as blank and
            // soft-deleting the real, still-encrypted note out from under the user.
            if (note == null) {
                Snackbar.make(requireView(), R.string.note_unavailable_locked, Snackbar.LENGTH_LONG)
                        .show();
                NavHostFragment.findNavController(this).popBackStack();
                return;
            }

            suppressAutoSave = true;
            noteTitle.setText(note.title);
            // A note saved without a title reads as "Untitled Note - <date>" everywhere else,
            // so the editor labels it the same way rather than showing a blank field — but as
            // a hint, so it stays as easy to name as it was on the day it was created.
            showUntitledHint(note.createdAt);
            pendingCollectionId = note.collectionId;
            noteCreatedAt = note.createdAt;
            collectionRepository.isLocked(note.collectionId, locked -> collectionLocked = locked);
            noteEditorView.loadSegments(segments);
            noteEditorView.setAudioClipTitle(clipTitle());
            suppressAutoSave = false;
            contentLoaded = true;
        });
        tagRepository.loadTagsForNote(noteId, tags -> {
            if (!isAdded()) return;
            currentTags = tags;
            renderTagRow();
        });
    }

    /** Puts the generated "Untitled Note - <date>" name in the title field's hint, where an empty
     *  title is one keystroke from being replaced rather than a sentence to delete first. */
    private void showUntitledHint(long createdAt) {
        noteTitle.setHint(NoteDisplayUtils.untitledWithDate(requireContext(), createdAt));
    }

    /** What a recording from this note is called once it is playing somewhere else — the note's
     *  title, or the same generated name the lists show it under while it hasn't got one. */
    private String clipTitle() {
        String title = noteTitle.getText().toString().trim();
        if (!title.isEmpty()) return title;
        CharSequence hint = noteTitle.getHint();
        return hint == null ? "" : hint.toString();
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
        // Neither a playing recording nor a reading in progress is touched here. Both belong to
        // process-wide players (AudioPlayback, ReadAloud) rather than to this screen, and following
        // the user out of the note is the point — the now-playing bar is where they get paused or
        // closed from once the note is behind them.
        autoSave();
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
        // Specifically *this* note's reading: another note left reading in the background is the
        // bar's business, and this menu offering to stop it would be a lie about whose voice it is.
        boolean speaking = ReadAloud.isReadingNote(noteId);
        playAloud.setTitle(speaking ? R.string.action_stop_reading : R.string.action_read_aloud);
        playAloud.setIcon(speaking ? R.drawable.ic_menu_pause : R.drawable.ic_menu_play);
        // Reading an empty note would just be silence, so the item goes away rather than misleading.
        // A note with only a recording in it still has something to play, and still offers this.
        playAloud.setVisible(speaking || !buildReadPlaylist().isEmpty());

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
            if (id == R.id.action_export) {
                export.showExportMenu(anchor);
                return true;
            }
            return false;
        });
        menu.show();
    }


    private void toggleReadAloud() {
        if (ReadAloud.isReadingNote(noteId)) {
            ReadAloud.stop();
        } else {
            // One voice at a time: a recording the user started by hand playing under a note being
            // read aloud is just noise, and both would be fighting for the same bar. The reading
            // plays this note's own recordings itself, in the order they sit in the note.
            AudioPlayback.get(requireContext()).close();
            ReadAloud.start(requireContext(), noteId, clipTitle(), buildReadPlaylist());
        }
    }

    /**
     * Opens the review screen for this note's Q&amp;A blocks.
     *
     * <p>Only blocks with <em>both</em> halves filled in become cards — a question with no answer
     * has nothing to turn over — so a note can hold Q&amp;A and still have no deck, and that's the
     * case the hint dialog covers. The save is forced through first because the review screen reads
     * the note back from storage: it needs the blocks' ids, and for a brand-new note, a row to read
     * at all.
     */
    private void openFlashcards() {
        List<NoteSegment> segments = noteEditorView.exportSegments();
        // A note whose blocks have all been emptied still has its old cards, so the deck is worth
        // opening even when there's nothing left to generate from.
        if (!hasFlashcards && FlashcardStore.reviewableQa(segments).isEmpty()) {
            QaBlockHintDialog.showForFlashcards(requireContext(), this::insertQaBlock);
            return;
        }

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
     * better than a message on the way out can.
     */
    private void openQuiz() {
        List<NoteSegment> segments = noteEditorView.exportSegments();
        int usable = FlashcardStore.reviewableQa(segments).size();
        if (!hasQuiz && usable < QuizRules.MIN_QA_BLOCKS) {
            QaBlockHintDialog.showForQuiz(requireContext(), usable, QuizRules.MIN_QA_BLOCKS,
                    this::insertQaBlock);
            return;
        }

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

    /**
     * Inserts a Q&amp;A block from somewhere other than the toolbar — currently the hint dialog's
     * "add one" button.
     *
     * <p>Identical to the toolbar item's action, and deliberately so: the block lands at the caret
     * (or at the end of the note when nothing is focused), and focusing its question field raises
     * the keyboard, which brings the real formatting bar into view with the icon the dialog was
     * just pointing at now genuinely on screen. That last part is why the hint dialog defers this
     * to its dismiss rather than running it from the button — see QaBlockHintDialog.
     */
    private void insertQaBlock() {
        noteEditorView.insertQaBlockAfterFocused();
        updateToolbarState();
        showKeyboardOnceWindowFocused();
    }

    /**
     * Re-asks for the keyboard once this window actually has focus again.
     *
     * <p>Focusing the new block asks for the IME itself, and that is enough when the insert came
     * from the toolbar — the keyboard is already up. It is not enough coming from the hint dialog:
     * the dialog's window is still being torn down at that point, and {@code showSoftInput} against
     * a window without focus is discarded. The block would arrive focused, with a caret, and no
     * keyboard — and so no formatting bar, which is the one thing the dialog had been pointing at.
     *
     * <p>A no-op when focus is already here, so the toolbar's own path costs nothing.
     */
    private void showKeyboardOnceWindowFocused() {
        View root = getView();
        if (root == null || root.hasWindowFocus()) return;

        root.getViewTreeObserver().addOnWindowFocusChangeListener(
                new ViewTreeObserver.OnWindowFocusChangeListener() {
                    @Override
                    public void onWindowFocusChanged(boolean hasFocus) {
                        if (!hasFocus) return;
                        root.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                        View focused = root.findFocus();
                        if (focused == null || !isAdded()) return;
                        InputMethodManager imm = (InputMethodManager)
                                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.showSoftInput(focused, InputMethodManager.SHOW_IMPLICIT);
                        }
                    }
                });
    }

    /** The body only — the title is often just the auto-generated "Untitled Note - <date>"
     *  placeholder, which shouldn't be read aloud (and, since it's never actually empty, would
     *  otherwise make a blank note look like it has something to say). */
    private ReadPlaylist buildReadPlaylist() {
        return noteEditorView.buildReadPlaylist();
    }

    /** Halts a reading in progress if the last thing it had to read just got deleted out from
     *  under it. Only this note's — emptying one note is no reason to silence another. */
    private void stopReadingIfNothingLeft() {
        if (ReadAloud.isReadingNote(noteId) && buildReadPlaylist().isEmpty()) {
            ReadAloud.stop();
        }
    }

    /** Long-press on the options button — lets the user swap out the engine's default
     *  ("robotic") voice for another one installed on the device. */
    private void showVoicePickerDialog() {
        List<Voice> voices = ReadAloud.availableVoices(requireContext());
        if (voices.isEmpty()) return; // TTS engine not ready yet, or no voices for this locale

        Voice current = ReadAloud.currentVoice(requireContext());
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
                    ReadAloud.setVoice(requireContext(), voices.get(which));
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
            noteId = NoteStore.newNoteId();
            // A reading can have been started before the note had an id; hand it the one just
            // minted so the menu still recognises the voice as this note's.
            ReadAloud.noteIdMinted(noteId);
            noteRepository.createNote(noteId, title, pendingCollectionId, () -> {
                if (isAdded()) renderTagRow();
            });
        } else if (!hasContent) {
            noteRepository.deleteNote(noteId, null);
            return;
        }

        noteRepository.saveNote(noteId, title, segments, new NoteStore.OnNoteSaved() {
            @Override public void onSaved() {
                if (onSaved != null) onSaved.run();
            }

            @Override public void onNeedsUnlock() {
                // The collection re-locked while this note was open — its key's authentication
                // window closed. Nothing was written and the editor still holds every character,
                // so this is an offer to retry, not a warning about lost work. onSaved is
                // deliberately not run: callers use it to hand the note off to another screen,
                // which would read a stale copy off disk.
                if (isAdded()) promptUnlockAndRetry(onSaved);
            }
        });
    }

    /**
     * Re-authenticates and saves again.
     *
     * <p>Offered as a Snackbar action rather than an immediate prompt: an auto-save firing on a
     * 500ms debounce must not throw a system dialog over someone who is still typing. If they
     * ignore it, the next save attempt offers it again, and leaving the screen without unlocking
     * costs the edits made since the collection re-locked — which is why the action is there.
     */
    private void promptUnlockAndRetry(Runnable onSaved) {
        // Set from the note itself once it loads, and from the arguments before that — either way
        // it is the collection this note belongs to, which is the one holding the key.
        String collectionId = pendingCollectionId;
        if (collectionId == null) return;

        Snackbar.make(requireView(), R.string.note_save_needs_unlock, Snackbar.LENGTH_LONG)
                .setAction(R.string.note_save_unlock_action, v ->
                        CollectionLock.unlock(requireActivity(), collectionId,
                                new CollectionLock.Listener() {
                                    @Override public void onUnlocked() {
                                        if (isAdded()) autoSave(onSaved);
                                    }

                                    @Override public void onFailed(boolean cancelled, CharSequence m) {}

                                    @Override public void onKeyGone() {}
                                }))
                .show();
    }

    private boolean hasRealContent(List<NoteSegment> segments) {
        for (NoteSegment segment : segments) {
            if (segment instanceof ImageSegment) return true;
            if (segment instanceof AudioSegment) return true;
            // A note whose only content is a Q&A block is a note with content — it used to survive
            // solely because the auto-generated title is never empty.
            if (segment instanceof QaSegment) return true;
            // And a note holding nothing but an attached board: the attachment is the content.
            // Without this, attaching a whiteboard to a blank note and leaving deletes the note.
            if (segment instanceof WhiteboardSegment) return true;
            if (segment instanceof TextSegment
                    && !((TextSegment) segment).content.toString().trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}