package mse.quill.ui.notes;

import android.graphics.Typeface;
import android.os.Bundle;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import androidx.appcompat.widget.Toolbar;

import mse.quill.R;

public class NoteEditorFragment extends Fragment {

    private EditText noteTitle;
    private EditText noteContent;
    private boolean isBoldActive = false;
    private boolean isItalicActive = false;
    private boolean isUnderlineActive = false;

    private Button boldButton;
    private Button italicButton;
    private Button underlineButton;

    private final int DEBOUNCE_TIME_MS = 500;

    private Button addFormatButton(LinearLayout container, String label, Runnable action) {
        Button btn = new Button(requireContext());
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(4, 0, 4, 0);
        btn.setLayoutParams(params);
        container.addView(btn);
        return btn;
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
        noteContent = view.findViewById(R.id.note_content);

        setupFormattingToolbar(view);

        Toolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setTitle("New Note");
        toolbar.setNavigationOnClickListener(v ->
                NavHostFragment.findNavController(this).navigateUp()
        );

        HorizontalScrollView formattingToolbar = view.findViewById(R.id.formatting_toolbar);

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;

            // When keyboard is open, imeHeight > 0
            if (imeHeight > 0) {
                formattingToolbar.setVisibility(View.VISIBLE);
                formattingToolbar.setTranslationY(-imeHeight + navBarHeight);
            } else {
                formattingToolbar.setVisibility(View.GONE);
                formattingToolbar.setTranslationY(0);
            }

            return insets;
        });

        ViewCompat.requestApplyInsets(view);

        noteContent.addTextChangedListener(new TextWatcher() {
            private final Handler handler = new Handler(Looper.getMainLooper());
            private Runnable saveRunnable;

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                // Apply active formatting to newly typed character
                int cursor = noteContent.getSelectionStart();
                if (cursor > 0) {
                    if (isBoldActive) {
                        s.setSpan(new StyleSpan(Typeface.BOLD),
                                cursor - 1, cursor,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    if (isItalicActive) {
                        s.setSpan(new StyleSpan(Typeface.ITALIC),
                                cursor - 1, cursor,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    if (isUnderlineActive) {
                        s.setSpan(new UnderlineSpan(),
                                cursor - 1, cursor,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }

                // Debounced auto-save
                if (saveRunnable != null) handler.removeCallbacks(saveRunnable);
                saveRunnable = () -> autoSave();
                handler.postDelayed(saveRunnable, DEBOUNCE_TIME_MS);
            }
        });
    }

    private void autoSave() {
        String title = noteTitle.getText().toString().trim();
        String content = noteContent.getText().toString().trim();
        // TODO: pass to ViewModel to save
    }

    private void setupFormattingToolbar(View view) {
        LinearLayout container = view.findViewById(R.id.formatting_buttons);

        boldButton = addFormatButton(container, "B", () -> toggleBold());
        italicButton = addFormatButton(container, "I", () -> toggleItalic());
        underlineButton = addFormatButton(container, "U", () -> toggleUnderline());
//        addFormatButton(container, "S", () -> toggleStrikethrough());
//        addFormatButton(container, "H1", () -> toggleHeading());
//        addFormatButton(container, "•", () -> toggleBullet());
//        addFormatButton(container, "🎤", () -> startVoiceMemo());
//        addFormatButton(container, "📷", () -> insertImage());
    }

    private void updateToolbarButtonStates() {
        // Change button appearance based on active state
        // For now just change alpha — proper styling can come later
        boldButton.setAlpha(isBoldActive ? 1.0f : 0.5f);
        italicButton.setAlpha(isItalicActive ? 1.0f : 0.5f);
        underlineButton.setAlpha(isUnderlineActive ? 1.0f : 0.5f);
    }

    private void toggleBold() {
        Editable text = noteContent.getText();
        int start = noteContent.getSelectionStart();
        int end = noteContent.getSelectionEnd();

        if (start != end) {
            // Text is selected — apply/remove to selection only
            StyleSpan[] spans = text.getSpans(start, end, StyleSpan.class);
            boolean alreadyBold = false;
            for (StyleSpan span : spans) {
                if (span.getStyle() == Typeface.BOLD) {
                    alreadyBold = true;
                    text.removeSpan(span);
                }
            }
            if (!alreadyBold) {
                text.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } else {
            // No selection — toggle bold mode for future typing
            isBoldActive = !isBoldActive;
            // Visual feedback — change button appearance
            updateToolbarButtonStates();
        }
    }

    private void toggleItalic() {
        Editable text = noteContent.getText();
        int start = noteContent.getSelectionStart();
        int end = noteContent.getSelectionEnd();

        if(start!=end){
            StyleSpan[] spans = text.getSpans(start, end, StyleSpan.class);
            boolean alreadyItalic = false;
            for (StyleSpan span : spans) {
                if (span.getStyle() == Typeface.ITALIC) {
                    alreadyItalic = true;
                    text.removeSpan(span);
                }
            }
            if (!alreadyItalic) {
                text.setSpan(
                        new StyleSpan(Typeface.ITALIC),
                        start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            }
        }else{
            // No selection — toggle bold mode for future typing
            isItalicActive = !isItalicActive;
            // Visual feedback — change button appearance
            updateToolbarButtonStates();
        }
    }

    private void toggleUnderline() {
        Editable text = noteContent.getText();
        int start = noteContent.getSelectionStart();
        int end = noteContent.getSelectionEnd();

        if(start!=end) {
            UnderlineSpan[] spans = text.getSpans(start, end, UnderlineSpan.class);
            if (spans.length > 0) {
                for (UnderlineSpan span : spans) text.removeSpan(span);
            } else {
                text.setSpan(new UnderlineSpan(), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }else {
            // No selection — toggle bold mode for future typing
            isUnderlineActive = !isUnderlineActive;
            // Visual feedback — change button appearance
            updateToolbarButtonStates();
        }
    }
}