package mse.quill.ui.flashcards;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;

import mse.quill.R;
import mse.quill.util.RelativeTime;
import mse.quill.data.FlashcardRepository;
import mse.quill.data.NoteRepository;
import mse.quill.data.model.Flashcard;
import mse.quill.data.serialization.MarkdownSerializer;

/**
 * Reviews the flashcards generated from one note's Q&amp;A blocks.
 *
 * <p>The screen takes a note id and nothing else. It reads the note back from storage rather than
 * being handed the editor's live segments, because the cards have to be reconciled against what was
 * actually saved — the ids in the note's Markdown are what a card's review history hangs off.
 */
public class FlashcardsFragment extends Fragment {

    public static final String ARG_NOTE_ID = "note_id";

    private TextView cardLabel;
    private TextView cardText;
    private TextView cardHint;
    private TextView cardPosition;
    private MaterialCardView card;
    private LinearProgressIndicator progress;
    private View gradingRow;
    private View messagePanel;
    private ImageView messageIcon;
    private TextView messageTitle;
    private TextView messageBody;
    private MaterialButton messagePrimary;
    private MaterialButton messageSecondary;

    private NoteRepository noteRepository;
    private FlashcardRepository flashcardRepository;

    private String noteId;
    private String noteTitle;
    private View deleteButton;
    private List<Flashcard> deck = new ArrayList<>();
    private ReviewSession session;
    private boolean answerShowing;
    private boolean flipping;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_flashcards, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        card = view.findViewById(R.id.card);
        cardLabel = view.findViewById(R.id.card_label);
        cardText = view.findViewById(R.id.card_text);
        cardHint = view.findViewById(R.id.card_hint);
        cardPosition = view.findViewById(R.id.card_position);
        progress = view.findViewById(R.id.progress);
        gradingRow = view.findViewById(R.id.grading_row);
        messagePanel = view.findViewById(R.id.message_panel);
        messageIcon = view.findViewById(R.id.message_icon);
        messageTitle = view.findViewById(R.id.message_title);
        messageBody = view.findViewById(R.id.message_body);
        messagePrimary = view.findViewById(R.id.message_primary_button);
        messageSecondary = view.findViewById(R.id.message_secondary_button);

        noteRepository = new NoteRepository(requireContext());
        flashcardRepository = new FlashcardRepository(requireContext());

        deleteButton = view.findViewById(R.id.delete_deck_button);
        deleteButton.setVisibility(View.GONE); // until we know there is a deck to delete
        deleteButton.setOnClickListener(v -> confirmDeleteDeck());

        view.findViewById(R.id.back_button).setOnClickListener(v -> leave());
        card.setOnClickListener(v -> revealAnswer());
        forwardTapsToCard(view.findViewById(R.id.card_scroll));
        view.findViewById(R.id.button_correct).setOnClickListener(v -> grade(true));
        view.findViewById(R.id.button_incorrect).setOnClickListener(v -> grade(false));

        // Without this the flip reads as a squash rather than a rotation: the default camera sits
        // so close to the view that a 90° turn foreshortens most of the card away.
        card.setCameraDistance(8000 * getResources().getDisplayMetrics().density);

        loadDeck();
    }

    /**
     * Makes the scrolling middle of the card turn it over too.
     *
     * <p>A {@code ScrollView} consumes taps whether or not it has anything to scroll, so the card's
     * own click listener only ever fired on its margins — which is most of the card refusing the
     * one gesture the screen is built around. Watching the touches on their way through, and not
     * consuming them, leaves a long answer scrollable while a tap still flips.
     */
    @SuppressLint("ClickableViewAccessibility") // the card is the click target for accessibility
    private void forwardTapsToCard(View scroll) {
        GestureDetector taps = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        revealAnswer();
                        return false;
                    }
                });
        scroll.setOnTouchListener((v, event) -> {
            taps.onTouchEvent(event);
            return false;
        });
    }

    private void loadDeck() {
        noteId = getArguments() != null ? getArguments().getString(ARG_NOTE_ID) : null;
        if (noteId == null) {
            leave();
            return;
        }
        noteRepository.loadNote(noteId, (note, segments) -> {
            if (!isAdded()) return;
            noteTitle = note != null ? note.title : null;
            flashcardRepository.syncFromNote(noteId, segments, cards -> {
                if (!isAdded()) return;
                deck = cards;
                deleteButton.setVisibility(deck.isEmpty() ? View.GONE : View.VISIBLE);
                startSession(ReviewSession.due(deck, System.currentTimeMillis()));
            });
        });
    }

    private void confirmDeleteDeck() {
        if (noteId == null) return;
        DeleteFlashcardsDialog.show(requireContext(), noteTitle, () ->
                flashcardRepository.deleteForNote(noteId, () -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), R.string.flashcards_deleted,
                            Toast.LENGTH_SHORT).show();
                    // Back to whichever screen sent us here — this one has nothing left to show,
                    // and a Snackbar on a screen we're leaving wouldn't be read.
                    leave();
                }));
    }

    // ── Session flow ───────────────────────────────────────────────────────

    private void startSession(List<Flashcard> cards) {
        if (deck.isEmpty()) {
            showMessage(R.string.flashcards_empty_title, getString(R.string.flashcards_empty_message),
                    null, 0, R.string.flashcards_action_back);
            return;
        }
        if (cards.isEmpty()) {
            showCaughtUp();
            return;
        }

        session = new ReviewSession(cards);
        messagePanel.setVisibility(View.GONE);
        card.setVisibility(View.VISIBLE);
        progress.setMax(session.deckSize());
        showQuestion(session.current(), false);
    }

    /** Turns the card over. One direction only — going back to the question would let a half-recalled
     *  answer be talked into a "correct", which is the one judgement the schedule depends on. */
    private void revealAnswer() {
        if (session == null || answerShowing || flipping || session.current() == null) return;
        Flashcard current = session.current();
        flip(() -> {
            answerShowing = true;
            cardLabel.setText(R.string.flashcard_label_answer);
            cardText.setText(MarkdownSerializer.fromMarkdown(current.back));
            cardHint.setVisibility(View.INVISIBLE);
            gradingRow.setVisibility(View.VISIBLE);
        });
    }

    private void grade(boolean correct) {
        if (session == null || !answerShowing || flipping) return;

        Flashcard current = session.current();
        if (current != null && session.isFirstAnswer()) {
            flashcardRepository.recordReview(current, correct, null);
        }
        session.answer(correct);

        if (session.isFinished()) {
            showSummary();
            return;
        }
        showQuestion(session.current(), true);
    }

    private void showQuestion(Flashcard flashcard, boolean animated) {
        if (flashcard == null) return;
        answerShowing = false;
        gradingRow.setVisibility(View.INVISIBLE);

        Runnable swap = () -> {
            cardLabel.setText(R.string.flashcard_label_question);
            cardText.setText(MarkdownSerializer.fromMarkdown(flashcard.front));
            cardHint.setVisibility(View.VISIBLE);
            updateProgress();
        };

        if (!animated) {
            swap.run();
            return;
        }
        // A fade, not a flip: the next card arriving is a different gesture from the same card
        // turning over, and using one animation for both makes the deck feel like one shuffling
        // card.
        card.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            swap.run();
            card.animate().alpha(1f).setDuration(160).start();
        }).start();
    }

    private void updateProgress() {
        progress.setProgressCompat(session.completed(), true);
        cardPosition.setText(getString(R.string.flashcard_position_format,
                session.position(), session.deckSize()));
    }

    // ── Non-card states ────────────────────────────────────────────────────

    private void showSummary() {
        int total = session.deckSize();
        int right = session.correctFirstTry();
        progress.setProgressCompat(total, true);
        cardPosition.setText(getString(R.string.flashcard_position_format, total, total));

        String body = session.missed() == 0
                ? getString(R.string.flashcards_summary_perfect)
                : getString(R.string.flashcards_summary_format, right, total);
        showMessage(R.string.flashcards_summary_title, body,
                () -> startSession(new ArrayList<>(deck)),
                R.string.flashcards_action_review_again,
                R.string.flashcards_action_back);
    }

    /** Nothing is due yet. The deck is still offered, because "I want to go over these now" is a
     *  perfectly good reason to ignore a schedule — it just isn't the default. */
    private void showCaughtUp() {
        long next = ReviewSession.earliestDue(deck);
        String body = next == 0
                ? getString(R.string.flashcards_caught_up_message)
                : getString(R.string.flashcards_caught_up_message_format,
                        RelativeTime.future(requireContext(), next));
        showMessage(R.string.flashcards_caught_up_title, body,
                () -> startSession(new ArrayList<>(deck)),
                R.string.flashcards_action_review_anyway,
                R.string.flashcards_action_back);
    }

    private void showMessage(int titleRes, String body, Runnable primaryAction,
                             int primaryLabelRes, int secondaryLabelRes) {
        card.setVisibility(View.GONE);
        gradingRow.setVisibility(View.INVISIBLE);
        messagePanel.setVisibility(View.VISIBLE);
        messageIcon.setImageResource(R.drawable.ic_flashcard);
        messageTitle.setText(titleRes);
        messageBody.setText(body);

        if (primaryAction == null) {
            messagePrimary.setVisibility(View.GONE);
        } else {
            messagePrimary.setVisibility(View.VISIBLE);
            messagePrimary.setText(primaryLabelRes);
            messagePrimary.setOnClickListener(v -> {
                card.setAlpha(1f);
                primaryAction.run();
            });
        }
        messageSecondary.setText(secondaryLabelRes);
        messageSecondary.setOnClickListener(v -> leave());
    }

    private void leave() {
        NavHostFragment.findNavController(this).navigateUp();
    }

    // ── Animation ──────────────────────────────────────────────────────────

    /** Half-turns the card away, swaps its face at the edge-on point, and turns it back. */
    private void flip(Runnable swapFace) {
        flipping = true;
        ObjectAnimator away = ObjectAnimator.ofFloat(card, "rotationY", 0f, 90f);
        away.setDuration(140);
        away.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                swapFace.run();
                // Starting the return from -90 rather than 90 keeps the rotation going the same
                // way; coming back from 90 would rewind the turn instead of completing it.
                card.setRotationY(-90f);
                ObjectAnimator back = ObjectAnimator.ofFloat(card, "rotationY", -90f, 0f);
                back.setDuration(180);
                back.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        flipping = false;
                    }
                });
                back.start();
            }
        });
        away.start();
    }
}
