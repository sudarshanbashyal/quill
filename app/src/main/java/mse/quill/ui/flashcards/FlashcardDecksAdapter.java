package mse.quill.ui.flashcards;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.format.DateUtils;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import mse.quill.R;
import mse.quill.data.model.FlashcardDeck;

/** The decks list: one row per note that has cards. */
public class FlashcardDecksAdapter extends RecyclerView.Adapter<FlashcardDecksAdapter.DeckHolder> {

    public interface Listener {
        void onDeckClicked(FlashcardDeck deck);
        void onDeleteClicked(FlashcardDeck deck);
    }

    private final Listener listener;
    private List<FlashcardDeck> decks = new ArrayList<>();

    public FlashcardDecksAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<FlashcardDeck> decks) {
        this.decks = decks;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public DeckHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new DeckHolder(FlashcardDeckRowView.build(parent.getContext()));
    }

    @Override
    public void onBindViewHolder(@NonNull DeckHolder holder, int position) {
        holder.bind(decks.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return decks.size();
    }

    static class DeckHolder extends RecyclerView.ViewHolder {

        private final FlashcardDeckRowView.Views views;

        DeckHolder(FlashcardDeckRowView.Views views) {
            super(views.root);
            this.views = views;
        }

        void bind(FlashcardDeck deck, Listener listener) {
            Context context = itemView.getContext();
            boolean hasDue = deck.due > 0;

            views.badge.setText(String.valueOf(deck.due));
            views.badge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
                    context, hasDue ? R.color.brand_purple : R.color.divider)));
            views.badge.setTextColor(ContextCompat.getColor(
                    context, hasDue ? R.color.text_on_brand : R.color.text_secondary));

            views.title.setText(deck.noteTitle);
            views.detail.setText(hasDue
                    ? context.getString(R.string.flashcards_deck_due_format, deck.due, deck.total)
                    : context.getString(R.string.flashcards_deck_none_due_format, deck.total));
            views.meta.setText(metaLine(context, deck, hasDue));

            views.root.setOnClickListener(v -> listener.onDeckClicked(deck));
            views.deleteButton.setOnClickListener(v -> listener.onDeleteClicked(deck));
        }

        /**
         * The second detail line answers whichever question the first one leaves open: with cards
         * waiting, how many of them have never been seen; with none waiting, when the deck comes
         * back.
         */
        private static String metaLine(Context context, FlashcardDeck deck, boolean hasDue) {
            if (hasDue && deck.unseen > 0) {
                return context.getString(R.string.flashcards_deck_unseen_format, deck.unseen);
            }
            if (deck.lastReviewedAt == null) {
                return context.getString(R.string.flashcards_deck_never_reviewed);
            }
            long now = System.currentTimeMillis();
            if (hasDue) {
                return context.getString(R.string.flashcards_deck_last_reviewed_format,
                        DateUtils.getRelativeTimeSpanString(
                                deck.lastReviewedAt, now, DateUtils.MINUTE_IN_MILLIS));
            }
            return context.getString(R.string.flashcards_deck_next_format,
                    DateUtils.getRelativeTimeSpanString(
                            deck.nextReview, now, DateUtils.MINUTE_IN_MILLIS));
        }
    }
}
