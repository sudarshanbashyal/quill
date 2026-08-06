package mse.quill.ui.home;

import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import mse.quill.data.model.Note;
import mse.quill.ui.tags.TagChipView;
import mse.quill.util.NoteDisplayUtils;
import mse.quill.util.RelativeTime;

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
        private final LinearLayout tagsContainer;

        NoteViewHolder(@NonNull NoteRowView.Views views) {
            super(views.root);
            titleView = views.titleView;
            timestampView = views.timestampView;
            tagsContainer = views.tagsContainer;
        }

        void bind(Note note) {
            titleView.setText(NoteDisplayUtils.resolveTitle(itemView.getContext(), note));

            timestampView.setText(RelativeTime.past(itemView.getContext(), note.updatedAt));
            TagChipView.render(itemView.getContext(), tagsContainer, note.tags);

            itemView.setOnClickListener(v -> listener.onNoteClicked(note));
            itemView.setOnLongClickListener(v -> {
                listener.onNoteLongPressed(note);
                return true;
            });
        }
    }
}
