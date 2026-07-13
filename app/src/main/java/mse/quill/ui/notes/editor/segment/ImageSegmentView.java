package mse.quill.ui.notes.editor.segment;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import mse.quill.ui.notes.editor.model.NoteSegment;

public class ImageSegmentView extends BaseSegmentView {

    private final ImageView imageView;
    private String filePath;

    public ImageSegmentView(Context context, String filePath) {
        super(context);
        this.filePath = filePath;

        setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        setOrientation(VERTICAL);

        imageView = new ImageView(context);
        imageView.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_START);

        // Load image
        imageView.setImageBitmap(BitmapFactory.decodeFile(filePath));

        // Tap image to insert text after it
        imageView.setOnClickListener(v -> {
            if (callback != null) callback.onRequestSplitAt(this, 0);
        });

        // Long press to delete
        imageView.setOnLongClickListener(v -> {
            if (callback != null) callback.onRequestDelete(this);
            return true;
        });

        addView(imageView);
    }

    public String getFilePath() { return filePath; }

    @Override
    public int getSegmentType() { return NoteSegment.TYPE_IMAGE; }

    @Override
    public Object getSegmentData() { return filePath; }
}