package mse.quill.ui.home;

import android.text.format.DateUtils;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import mse.quill.R;
import mse.quill.data.model.Note;

public class NotesAdapter extends RecyclerView.Adapter<NotesAdapter.NoteViewHolder> {

    public interface Listener {
        void onNoteClicked(Note note);
        void onNoteLongPressed(Note note);
    }

    private final Listener listener;
    private final List<Note> notes = new ArrayList<>();

    public NotesAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<Note> newNotes) {
        notes.clear();
        notes.addAll(newNotes);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public NoteViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new NoteViewHolder(NoteRowView.build(parent.getContext()));
    }

    @Override
    public void onBindViewHolder(@NonNull NoteViewHolder holder, int position) {
        holder.bind(notes.get(position));
    }

    @Override
    public int getItemCount() {
        return notes.size();
    }

    class NoteViewHolder extends RecyclerView.ViewHolder {
        private final TextView titleView;
        private final TextView timestampView;

        NoteViewHolder(@NonNull NoteRowView.Views views) {
            super(views.root);
            titleView = views.titleView;
            timestampView = views.timestampView;
        }

        void bind(Note note) {
            boolean hasTitle = note.title != null && !note.title.trim().isEmpty();
            boolean hasPreview = note.preview != null && !note.preview.trim().isEmpty();

            titleView.setText(hasTitle ? note.title.trim()
                    : hasPreview ? note.preview.trim() : itemView.getContext().getString(R.string.untitled_note));

            timestampView.setText(DateUtils.getRelativeTimeSpanString(
                    note.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));

            itemView.setOnClickListener(v -> listener.onNoteClicked(note));
            itemView.setOnLongClickListener(v -> {
                listener.onNoteLongPressed(note);
                return true;
            });
        }
    }
}
