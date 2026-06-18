package mse.quill.ui.notes.editor;

import android.text.TextPaint;
import android.text.style.CharacterStyle;

public class ImagePathSpan extends CharacterStyle {

    private final String filePath;

    public ImagePathSpan(String filePath) {
        this.filePath = filePath;
    }

    public String getFilePath() {
        return filePath;
    }

    @Override
    public void updateDrawState(TextPaint tp) {
        // No visual effect — this span is just metadata
    }
}