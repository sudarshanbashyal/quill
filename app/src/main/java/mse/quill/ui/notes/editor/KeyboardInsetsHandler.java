package mse.quill.ui.notes.editor;

import android.view.View;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class KeyboardInsetsHandler {

    public interface KeyboardListener {
        void onKeyboardShown(int height);
        void onKeyboardHidden();
    }

    public static void attach(View rootView, KeyboardListener listener) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            int imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            if (imeHeight > 0) {
                listener.onKeyboardShown(imeHeight - navBarHeight);
            } else {
                listener.onKeyboardHidden();
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(rootView);
    }
}