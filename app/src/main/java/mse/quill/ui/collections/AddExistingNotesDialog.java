package mse.quill.ui.collections;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.LinearLayout;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import mse.quill.R;
import mse.quill.data.NoteStore;
import mse.quill.data.model.Note;
import mse.quill.util.NoteDisplayUtils;
import mse.quill.ui.common.MaxHeightScrollView;
import mse.quill.ui.common.TextFieldUtils;

/**
 * Dialog for finding existing notes (from anywhere in the app) and adding them to a collection:
 * a search field filters a checklist of candidate notes, matching the idiom already used by
 * {@link mse.quill.ui.tags.TagPickerDialog}.
 */
public final class AddExistingNotesDialog {

    private AddExistingNotesDialog() {}

    public static void show(Context context, NoteStore noteRepository, String collectionId,
                             List<Note> candidateNotes, Runnable onDone) {
        Set<String> selectedIds = new HashSet<>();
        int pad = dp(context, R.dimen.spacing_lg);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        TextInputLayout searchField = TextFieldUtils.outlinedField(context, R.string.search_hint_notes);
        content.addView(searchField);

        LinearLayout checklistContainer = new LinearLayout(context);
        checklistContainer.setOrientation(LinearLayout.VERTICAL);

        // Tall enough for the notes there are, capped for the notes there might be. It used to be
        // a fixed height, so a collection with three candidates opened a dialog sized for fifty.
        MaxHeightScrollView scroll = new MaxHeightScrollView(context);
        scroll.setMaxHeight(dp(context, R.dimen.note_picker_max_height));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = dp(context, R.dimen.spacing_md);
        scroll.setLayoutParams(scrollParams);
        scroll.addView(checklistContainer);
        content.addView(scroll);

        Runnable rebuild = () -> {
            String query = searchField.getEditText().getText().toString().trim().toLowerCase(Locale.getDefault());
            checklistContainer.removeAllViews();
            for (Note note : candidateNotes) {
                String title = NoteDisplayUtils.resolveTitle(context, note).toLowerCase(Locale.getDefault());
                if (!query.isEmpty() && !title.contains(query)) continue;
                checklistContainer.addView(buildNoteRow(context, note, selectedIds));
            }
        };
        rebuild.run();

        searchField.getEditText().addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { rebuild.run(); }
        });

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_add_existing_notes_title)
                .setView(content)
                .setPositiveButton(R.string.action_add, (dialog, which) ->
                        assignSequentially(noteRepository, new ArrayList<>(selectedIds), collectionId, onDone))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Assigns notes one at a time on the shared diskIO executor, running {@code onDone} only after the last. */
    private static void assignSequentially(NoteStore noteRepository, List<String> noteIds,
                                            String collectionId, Runnable onDone) {
        if (noteIds.isEmpty()) {
            if (onDone != null) onDone.run();
            return;
        }
        for (int i = 0; i < noteIds.size(); i++) {
            boolean isLast = i == noteIds.size() - 1;
            noteRepository.assignCollection(noteIds.get(i), collectionId, isLast ? onDone : null);
        }
    }

    private static MaterialCheckBox buildNoteRow(Context context, Note note, Set<String> selectedIds) {
        MaterialCheckBox checkBox = new MaterialCheckBox(context);
        checkBox.setText(NoteDisplayUtils.resolveTitle(context, note));
        checkBox.setChecked(selectedIds.contains(note.id));
        checkBox.setOnCheckedChangeListener((button, isChecked) -> {
            if (isChecked) selectedIds.add(note.id);
            else selectedIds.remove(note.id);
        });
        return checkBox;
    }

    private static int dp(Context context, int dimenRes) {
        return context.getResources().getDimensionPixelSize(dimenRes);
    }
}
