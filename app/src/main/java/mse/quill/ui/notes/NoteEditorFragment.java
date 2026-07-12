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
import androidx.appcompat.widget.Toolbar;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import mse.quill.R;
import mse.quill.data.NoteRepository;
import mse.quill.ui.notes.editor.FormattingToolbarController;
import mse.quill.ui.notes.editor.ImageEmbedder;
import mse.quill.ui.notes.editor.KeyboardInsetsHandler;
import mse.quill.ui.notes.editor.NoteEditorView;
import mse.quill.ui.notes.editor.model.ImageSegment;
import mse.quill.ui.notes.editor.model.NoteSegment;
import mse.quill.ui.notes.editor.model.TextSegment;

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

    private NoteRepository noteRepository;
    private String noteId;
    private String pendingCollectionId;
    private final AtomicBoolean isCreatingNote = new AtomicBoolean(false);
    private boolean suppressAutoSave = false;

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

        noteRepository = new NoteRepository(requireContext());

        Bundle args = getArguments();
        noteId = args != null ? args.getString(ARG_NOTE_ID) : null;
        pendingCollectionId = args != null ? args.getString(ARG_COLLECTION_ID) : null;

        noteEditorView.setContentChangeListener(this::scheduleAutoSave);
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

                    @Override
                    public void onImageRequested() {
                        showImageSourceDialog();
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
        autoSave();
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
            if (segment instanceof TextSegment
                    && !((TextSegment) segment).content.toString().trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}