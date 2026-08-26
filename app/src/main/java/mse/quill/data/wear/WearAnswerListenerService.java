package mse.quill.data.wear;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import mse.quill.data.model.Flashcard;
import mse.quill.data.AppExecutors;
import mse.quill.data.FlashcardRepository;
import mse.quill.data.AnswerEventKeys;
import mse.quill.data.FlashcardScheduler;

/**
 * Receives answers given on the watch and replays them through the phone's scheduler.
 *
 * <p>The return half of {@link WearProjectionPublisher}. The watch never computes SM-2 state — it
 * holds a {@code DueCard}, which deliberately has no easiness, interval or repetition count — so an
 * answer arrives as the bare fact "this card, recalled or not, at this time" and the phone's
 * {@link FlashcardScheduler} advances the schedule. That is what keeps the two devices from ever
 * disagreeing about a card's easiness: only one of them has an opinion.
 *
 * <p>Answers arrive as messages rather than as a {@code DataItem} because they are events. Two
 * answers to the same card are two facts, and an item keyed by card id would have the second
 * overwrite the first before the phone ever woke up to read it.
 */
public class WearAnswerListenerService extends WearableListenerService {

    private static final String TAG = "WearAnswer";

    /**
     * <p>Runs on a background binder thread, which is why the database read below is a direct
     * blocking call rather than a hop onto {@code AppExecutors} — the thread this arrives on is
     * already the right kind.
     */
    @Override
    public void onMessageReceived(@NonNull MessageEvent event) {
        if (!AnswerEventKeys.PATH.equals(event.getPath())) return;

        DataMap map;
        try {
            map = DataMap.fromByteArray(event.getData());
        } catch (RuntimeException e) {
            // A malformed payload is not something a retry fixes, and there is no user to tell.
            Log.w(TAG, "Dropping an answer with an unreadable payload", e);
            return;
        }

        String cardId = map.getString(AnswerEventKeys.KEY_CARD_ID);
        if (cardId == null) {
            Log.w(TAG, "Dropping an answer with no card id");
            return;
        }
        boolean correct = map.getBoolean(AnswerEventKeys.KEY_CORRECT);

        // Falling back to now would be worse than it looks: it silently anchors the interval to
        // delivery time, which is exactly the corruption the recordReview overload exists to
        // prevent. An event without a timestamp is malformed, so drop it and say so.
        long answeredAt = map.getLong(AnswerEventKeys.KEY_ANSWERED_AT, 0L);
        if (answeredAt <= 0L) {
            Log.w(TAG, "Dropping an answer with no timestamp for card " + cardId);
            return;
        }

        FlashcardRepository repository = new FlashcardRepository(getApplicationContext());
        Flashcard card = repository.loadByIdSync(cardId);
        if (card == null) {
            // The card was deleted on the phone while the watch was holding a projection naming
            // it. The watch's copy is simply stale; there is nothing to advance.
            Log.w(TAG, "Dropping an answer for a card that no longer exists: " + cardId);
            return;
        }

        // Republishes the projection when the write lands, so the tile and the complication drop
        // to the new count without the watch having to ask.
        repository.recordReview(card, correct, answeredAt, null);
    }
}
