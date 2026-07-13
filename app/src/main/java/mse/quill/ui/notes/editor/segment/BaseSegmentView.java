package mse.quill.ui.notes.editor.segment;

import android.content.Context;
import android.widget.LinearLayout;

public abstract class BaseSegmentView extends LinearLayout {

    public interface SegmentCallback {
        void onRequestSplitAt(BaseSegmentView segment, int cursorPosition);
        void onRequestDelete(BaseSegmentView segment);
        void onRequestMergeWithPrevious(BaseSegmentView segment);
        void onContentChanged();
    }

    protected SegmentCallback callback;

    public BaseSegmentView(Context context) {
        super(context);
    }

    public void setCallback(SegmentCallback callback) {
        this.callback = callback;
    }

    public abstract int getSegmentType();
    public abstract Object getSegmentData();
}