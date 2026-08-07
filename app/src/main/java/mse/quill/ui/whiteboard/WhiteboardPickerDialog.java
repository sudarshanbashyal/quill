package mse.quill.ui.whiteboard;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;
import java.util.Locale;

import mse.quill.R;
import mse.quill.data.model.Whiteboard;
import mse.quill.util.MaxHeightScrollView;
import mse.quill.util.NoteDisplayUtils;
import mse.quill.util.RelativeTime;
import mse.quill.util.TextFieldUtils;

/**
 * Picks an existing whiteboard — used when attaching one to a note.
 *
 * <p>Search field over a filtered list, the same idiom as {@code AddExistingNotesDialog} and
 * {@code TagPickerDialog}, because by the time you have a term's worth of boards a plain list is
 * no more use than none. Rows carry the drawing as well as the name: boards are often left
 * untitled, and "Untitled Whiteboard - Aug 7" three times over tells you nothing about which one
 * had the diagram on it.
 */
public final class WhiteboardPickerDialog {

    public interface OnPicked {
        void onPicked(Whiteboard whiteboard);
    }

    private WhiteboardPickerDialog() {}

    public static void show(Context context, List<Whiteboard> whiteboards, OnPicked onPicked) {
        int pad = dimen(context, R.dimen.spacing_lg);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        TextInputLayout searchField =
                TextFieldUtils.outlinedField(context, R.string.whiteboard_search_hint);
        content.addView(searchField);

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        MaxHeightScrollView scroll = new MaxHeightScrollView(context);
        scroll.setMaxHeight(dimen(context, R.dimen.note_picker_max_height));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = dimen(context, R.dimen.spacing_md);
        scroll.setLayoutParams(scrollParams);
        scroll.addView(list);
        content.addView(scroll);

        TextView empty = new TextView(context);
        empty.setText(R.string.whiteboard_search_no_match);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dimen(context, R.dimen.spacing_md), 0, dimen(context, R.dimen.spacing_md));
        empty.setAlpha(0.7f);
        empty.setVisibility(android.view.View.GONE);
        content.addView(empty);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.whiteboard_import_title)
                .setView(content)
                .setNegativeButton(R.string.action_cancel, null)
                .create();

        Runnable rebuild = () -> {
            String query = searchField.getEditText().getText().toString()
                    .trim().toLowerCase(Locale.getDefault());
            list.removeAllViews();
            int shown = 0;
            for (Whiteboard board : whiteboards) {
                String name = NoteDisplayUtils.resolveWhiteboardTitle(context, board);
                if (!query.isEmpty()
                        && !name.toLowerCase(Locale.getDefault()).contains(query)) {
                    continue;
                }
                list.addView(buildRow(context, board, name, () -> {
                    dialog.dismiss();
                    onPicked.onPicked(board);
                }));
                shown++;
            }
            empty.setVisibility(shown == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        };
        rebuild.run();

        searchField.getEditText().addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { rebuild.run(); }
        });

        dialog.show();
    }

    private static android.view.View buildRow(Context context, Whiteboard board, String name,
                                              Runnable onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padding = dimen(context, R.dimen.spacing_sm);
        row.setPadding(0, padding, 0, padding);
        row.setClickable(true);
        row.setOnClickListener(v -> onClick.run());

        ImageView preview = new ImageView(context);
        int size = dimen(context, R.dimen.whiteboard_picker_preview_size);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(size, size);
        previewParams.setMarginEnd(dimen(context, R.dimen.spacing_md));
        preview.setLayoutParams(previewParams);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundColor(context.getColor(R.color.surface_container));
        preview.setImageResource(R.drawable.ic_section_whiteboard);
        WhiteboardThumbnails.load(context, board, thumbnail -> {
            if (thumbnail != null) preview.setImageBitmap(thumbnail);
        });
        row.addView(preview);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(context);
        title.setText(name);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(context.getColor(R.color.text_primary));
        labels.addView(title);

        TextView updated = new TextView(context);
        updated.setText(context.getString(R.string.updated_relative_format,
                RelativeTime.past(context, board.updatedAt)));
        updated.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        updated.setAlpha(0.7f);
        labels.addView(updated);

        row.addView(labels);
        return row;
    }

    private static int dimen(Context context, int dimenRes) {
        return context.getResources().getDimensionPixelSize(dimenRes);
    }
}
