package mse.quill.ui.notes.editor;

import android.widget.Button;
import android.widget.LinearLayout;

public class FormattingToolbarController {

    public interface FormatListener {
        void onBoldToggled();
        void onItalicToggled();
        void onUnderlineToggled();
        void onImageRequested();
    }

    private final Button boldButton;
    private final Button italicButton;
    private final Button underlineButton;

    public FormattingToolbarController(LinearLayout container, FormatListener listener) {
        boldButton = addButton(container, "B", listener::onBoldToggled);
        italicButton = addButton(container, "I", listener::onItalicToggled);
        underlineButton = addButton(container, "U", listener::onUnderlineToggled);
        addButton(container, "📷", listener::onImageRequested);  // ← add this
    }

    public void updateState(boolean bold, boolean italic, boolean underline) {
        boldButton.setAlpha(bold ? 1.0f : 0.5f);
        italicButton.setAlpha(italic ? 1.0f : 0.5f);
        underlineButton.setAlpha(underline ? 1.0f : 0.5f);
    }

    private Button addButton(LinearLayout container, String label, Runnable action) {
        Button btn = new Button(container.getContext());
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
}