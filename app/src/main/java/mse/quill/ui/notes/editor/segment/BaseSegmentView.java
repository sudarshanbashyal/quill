package mse.quill.ui.notes.editor.segment;

import android.content.Context;
import android.widget.LinearLayout;

public abstract class BaseSegmentView extends LinearLayout {

    public interface SegmentCallback {
        void onRequestSplitAt(BaseSegmentView segment, int cursorPosition);
        void onRequestDelete(BaseSegmentView segment);
        void onRequestMergeWithPrevious(BaseSegmentView segment);
        void onContentChanged();
        /** The caret moved without the text changing — line-scoped toolbar state depends on it. */
        void onSelectionChanged();
        /** Save this segment's media out of the app's private storage. Routed up rather than
         *  handled in the view because it may need a runtime permission, which only the host
         *  fragment can ask for; the outcome comes back so the view can report it where the user
         *  is actually looking. */
        void onRequestExport(BaseSegmentView segment, ExportResult result);
    }

    /** Delivered on the main thread once an export finishes (or is refused). */
    public interface ExportResult {
        void onExportFinished(boolean saved);
    }

    protected SegmentCallback callback;

    /** Stable across a load → edit → save cycle, so the {@code quill://} reference a media
     *  segment is written as in the note's Markdown keeps pointing at the same asset row. */
    private final String segmentId;

    public BaseSegmentView(Context context) {
        this(context, null);
    }

    public BaseSegmentView(Context context, String segmentId) {
        super(context);
        this.segmentId = segmentId != null ? segmentId : java.util.UUID.randomUUID().toString();
    }

    public String getSegmentId() {
        return segmentId;
    }

    public void setCallback(SegmentCallback callback) {
        this.callback = callback;
    }

    public abstract int getSegmentType();
    public abstract Object getSegmentData();
}