package mse.quill.ui.notes;

import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;
import java.util.Locale;

import mse.quill.R;
import mse.quill.data.NoteStore;
import mse.quill.util.MaxHeightScrollView;
import mse.quill.util.NoteDisplayUtils;
import mse.quill.util.TextFieldUtils;

/**
 * Picks the note a new deck or quiz should be made from.
 *
 * <p>Search field over a filtered list of tappable rows — the idiom {@code WhiteboardPickerDialog}
 * and {@code AddExistingNotesDialog} already use, so the third picker in the app doesn't invent a
 * fourth shape. Single-select: there is no "add" button, because picking a note <em>is</em> the
 * decision and the screen it leads to is the confirmation.
 *
 * <p>Each row carries its Q&amp;A count. That is the number the whole question turns on — a quiz
 * needs five of them and a deck needs one — and without it the list is a set of note titles the
 * user has to remember the insides of. It also quietly teaches the rule: a note sitting at "4 Q&amp;A
 * blocks" in a picker that wants five explains the minimum better than a sentence about it would.
 */
public final class NoteQaPickerDialog {

    public interface OnPicked {
        void onPicked(NoteStore.QaCandidate candidate);
    }

    private NoteQaPickerDialog() {}

    public static void show(Context context, int titleRes,
                            List<NoteStore.QaCandidate> candidates, OnPicked onPicked) {
        int pad = dimen(context, R.dimen.spacing_lg);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        TextInputLayout searchField =
                TextFieldUtils.outlinedField(context, R.string.search_hint_notes);
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

        TextView noMatch = new TextView(context);
        noMatch.setText(R.string.qa_picker_no_match);
        noMatch.setGravity(Gravity.CENTER);
        noMatch.setPadding(0, dimen(context, R.dimen.spacing_md), 0, dimen(context, R.dimen.spacing_md));
        noMatch.setAlpha(0.7f);
        noMatch.setVisibility(View.GONE);
        content.addView(noMatch);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(titleRes)
                .setView(content)
                .setNegativeButton(R.string.action_cancel, null)
                .create();

        Runnable rebuild = () -> {
            String query = searchField.getEditText().getText().toString()
                    .trim().toLowerCase(Locale.getDefault());
            list.removeAllViews();
            int shown = 0;
            for (NoteStore.QaCandidate candidate : candidates) {
                String title = NoteDisplayUtils.resolveTitle(context, candidate.note);
                if (!query.isEmpty()
                        && !title.toLowerCase(Locale.getDefault()).contains(query)) {
                    continue;
                }
                list.addView(buildRow(context, candidate, title, () -> {
                    dialog.dismiss();
                    onPicked.onPicked(candidate);
                }));
                shown++;
            }
            noMatch.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
        };
        rebuild.run();

        searchField.getEditText().addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { rebuild.run(); }
        });

        dialog.show();
    }

    private static View buildRow(Context context, NoteStore.QaCandidate candidate,
                                 String title, Runnable onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        int padding = dimen(context, R.dimen.spacing_sm);
        row.setPadding(0, padding, 0, padding);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> onClick.run());

        TextView name = new TextView(context);
        name.setText(title);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTextColor(context.getColor(R.color.text_primary));
        row.addView(name);

        TextView blocks = new TextView(context);
        blocks.setText(context.getResources().getQuantityString(
                R.plurals.qa_picker_blocks, candidate.usableQa, candidate.usableQa));
        blocks.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        blocks.setTextColor(context.getColor(R.color.text_secondary));
        row.addView(blocks);

        return row;
    }

    private static int dimen(Context context, int dimenRes) {
        return context.getResources().getDimensionPixelSize(dimenRes);
    }
}
