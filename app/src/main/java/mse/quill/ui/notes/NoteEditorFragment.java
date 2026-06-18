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

import mse.quill.R;
import mse.quill.ui.notes.editor.FormattingToolbarController;
import mse.quill.ui.notes.editor.ImageEmbedder;
import mse.quill.ui.notes.editor.KeyboardInsetsHandler;
import mse.quill.ui.notes.editor.RichTextEditor;

public class NoteEditorFragment extends Fragment {

    private EditText noteTitle;
    private EditText noteContent;
    private RichTextEditor richTextEditor;
    private FormattingToolbarController toolbarController;
    private HorizontalScrollView formattingToolbar;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable saveRunnable;

    private ImageEmbedder imageEmbedder;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_note_editor, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        noteTitle = view.findViewById(R.id.note_title);
        noteContent = view.findViewById(R.id.note_content);
        formattingToolbar = view.findViewById(R.id.formatting_toolbar);

        richTextEditor = new RichTextEditor(noteContent);

        imageEmbedder = new ImageEmbedder(this, new ImageEmbedder.ImageResultListener() {
            @Override
            public void onImageReady(Bitmap bitmap, String filePath) {
                richTextEditor.insertImage(bitmap, filePath, noteContent.getWidth());
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
                        richTextEditor.toggleBold();
                        toolbarController.updateState(
                                richTextEditor.isBoldActive(),
                                richTextEditor.isItalicActive(),
                                richTextEditor.isUnderlineActive()
                        );
                    }
                    @Override public void onItalicToggled() {
                        richTextEditor.toggleItalic();
                        toolbarController.updateState(
                                richTextEditor.isBoldActive(),
                                richTextEditor.isItalicActive(),
                                richTextEditor.isUnderlineActive()
                        );
                    }
                    @Override public void onUnderlineToggled() {
                        richTextEditor.toggleUnderline();
                        toolbarController.updateState(
                                richTextEditor.isBoldActive(),
                                richTextEditor.isItalicActive(),
                                richTextEditor.isUnderlineActive()
                        );
                    }

                    @Override
                    public void onImageRequested() {
                        showImageSourceDialog();
                    }
                }
        );

        setupToolbar(view);
        setupTextWatcher();
        setupKeyboardBehaviour(view);
    }

    private void setupToolbar(View view) {
        Toolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setTitle("New Note");
        toolbar.setNavigationOnClickListener(v ->
                NavHostFragment.findNavController(this).navigateUp()
        );
    }

    private void setupTextWatcher() {
        noteContent.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                richTextEditor.applyActiveFormats(s);
                scheduleAutoSave();
            }
        });
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

    private void scheduleAutoSave() {
        if (saveRunnable != null) handler.removeCallbacks(saveRunnable);
        saveRunnable = this::autoSave;
        handler.postDelayed(saveRunnable, 500);
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
        // TODO: viewModel.saveNote(title, richTextEditor.serialiseContent())
    }
}