package mse.quill.ui.collections;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import mse.quill.R;
import mse.quill.data.NoteRepository;
import mse.quill.data.model.Note;
import mse.quill.util.NoteDisplayUtils;

/**
 * Dialog for finding existing notes (from anywhere in the app) and adding them to a collection:
 * a search field filters a checklist of candidate notes, matching the idiom already used by
 * {@link mse.quill.ui.tags.TagPickerDialog}.
 */
public final class AddExistingNotesDialog {

    private AddExistingNotesDialog() {}

    public static void show(Context context, NoteRepository noteRepository, String collectionId,
                             List<Note> candidateNotes, Runnable onDone) {
        Set<String> selectedIds = new HashSet<>();
        int pad = dp(context, R.dimen.spacing_lg);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        EditText searchInput = new EditText(context);
        searchInput.setHint(R.string.search_hint);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        content.addView(searchInput);

        LinearLayout checklistContainer = new LinearLayout(context);
        checklistContainer.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(context);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, R.dimen.note_picker_max_height));
        scrollParams.topMargin = dp(context, R.dimen.spacing_md);
        scroll.setLayoutParams(scrollParams);
        scroll.addView(checklistContainer);
        content.addView(scroll);

        Runnable rebuild = () -> {
            String query = searchInput.getText().toString().trim().toLowerCase(Locale.getDefault());
            checklistContainer.removeAllViews();
            for (Note note : candidateNotes) {
                String title = NoteDisplayUtils.resolveTitle(context, note).toLowerCase(Locale.getDefault());
                if (!query.isEmpty() && !title.contains(query)) continue;
                checklistContainer.addView(buildNoteRow(context, note, selectedIds));
            }
        };
        rebuild.run();

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { rebuild.run(); }
        });

        new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_add_existing_notes_title)
                .setView(content)
                .setPositiveButton(R.string.action_add, (dialog, which) ->
                        assignSequentially(noteRepository, new ArrayList<>(selectedIds), collectionId, onDone))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Assigns notes one at a time on the shared diskIO executor, running {@code onDone} only after the last. */
    private static void assignSequentially(NoteRepository noteRepository, List<String> noteIds,
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

    private static CheckBox buildNoteRow(Context context, Note note, Set<String> selectedIds) {
        CheckBox checkBox = new CheckBox(context);
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
