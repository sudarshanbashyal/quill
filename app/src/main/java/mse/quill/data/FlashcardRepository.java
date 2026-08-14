package mse.quill.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import mse.quill.data.model.DueCard;
import mse.quill.data.model.Flashcard;
import mse.quill.data.model.FlashcardDeck;
import mse.quill.data.serialization.MarkdownSerializer;
import mse.quill.data.serialization.NoteDocument;
import mse.quill.ui.notes.editor.model.NoteSegment;
import mse.quill.ui.notes.editor.model.QaSegment;
import mse.quill.util.NoteDisplayUtils;

/**
 * The flashcards generated from a note's Q&amp;A blocks, and their review schedule.
 *
 * <p>Generation is a sync, not an import: a block keeps its id in the note's Markdown, so re-running
 * it over an edited note updates that block's card text and leaves its SM-2 columns alone. A card
 * whose block has since been deleted is deliberately <em>not</em> removed — someone's review history
 * shouldn't evaporate because a note got tidied up — it simply stops appearing in the note's deck.
 */
public class FlashcardRepository {

    public interface OnDeckLoaded { void onLoaded(List<Flashcard> deck); }
    public interface OnDecksLoaded { void onLoaded(List<FlashcardDeck> decks); }
    public interface OnCounted { void onCounted(int count); }

    private final AppDatabase appDatabase;
    private final AppExecutors executors;
    /** Held only so a review can re-publish the Wear projection; see {@link #recordReview}. */
    private final Context appContext;

    public FlashcardRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.appDatabase = AppDatabase.getInstance(appContext);
        this.executors = AppExecutors.getInstance();
    }

    /**
     * The Q&amp;A blocks a deck can actually be built from: both halves have to say something. A
     * question with no answer isn't a card — there'd be nothing to turn over — and it's a normal
     * intermediate state while writing a note, so it's skipped rather than flagged.
     */
    public static List<QaSegment> reviewableQa(List<NoteSegment> segments) {
        List<QaSegment> reviewable = new ArrayList<>();
        if (segments == null) return reviewable;
        for (NoteSegment segment : segments) {
            if (!(segment instanceof QaSegment)) continue;
            QaSegment qa = (QaSegment) segment;
            if (isBlank(qa.question) || isBlank(qa.answer)) continue;
            reviewable.add(qa);
        }
        return reviewable;
    }

    private static boolean isBlank(CharSequence text) {
        return text == null || text.toString().trim().isEmpty();
    }

    /**
     * Brings the note's cards in line with its Q&amp;A blocks and hands back the resulting deck, in
     * the order the blocks appear in the note.
     */
    public void syncFromNote(String noteId, List<NoteSegment> segments, OnDeckLoaded cb) {
        List<QaSegment> reviewable = reviewableQa(segments);
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            Map<String, Flashcard> existing = loadBySegmentIdSync(db, noteId);
            long now = System.currentTimeMillis();

            List<Flashcard> deck = new ArrayList<>();
            // Whether the watch's copy is now wrong. Tracked rather than assumed, because this
            // method runs on every note save and most saves touch no Q&A block at all.
            boolean projectionChanged = false;
            db.beginTransaction();
            try {
                for (QaSegment qa : reviewable) {
                    Flashcard card = existing.get(qa.id);
                    String front = MarkdownSerializer.toMarkdown(qa.question);
                    String back = MarkdownSerializer.toMarkdown(qa.answer);

                    if (card == null) {
                        card = new Flashcard();
                        card.id = UUID.randomUUID().toString();
                        card.noteId = noteId;
                        card.sourceSegmentId = qa.id;
                        card.front = front;
                        card.back = back;
                        FlashcardScheduler.initialise(card, now);
                        db.insert("flashcards", null, toValues(card));
                        // initialise sets nextReview = now, so a new card is due the moment it
                        // exists — which is exactly why this has to reach the watch.
                        projectionChanged = true;
                    } else if (!front.equals(card.front) || !back.equals(card.back)) {
                        projectionChanged = true;
                        // Text only. Rewriting the schedule here would mean fixing a typo in a
                        // question silently threw away everything known about how well it's known.
                        card.front = front;
                        card.back = back;
                        ContentValues cv = new ContentValues();
                        cv.put("front", front);
                        cv.put("back", back);
                        db.update("flashcards", cv, "id = ?", new String[]{card.id});
                    }
                    deck.add(card);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            if (cb != null) executors.mainThread(() -> cb.onLoaded(deck));

            // After the callback and outside the transaction, matching recordReview: the editor
            // should return at the speed of the database write, not of a Data Layer round trip.
            //
            // Without this the watch only learned about new cards on the next cold start of
            // MainActivity, the next answered card, or the next daily worker run — so making a
            // deck on the phone and looking at the wrist showed "all caught up", which is the one
            // answer that is definitely wrong.
            if (projectionChanged) {
                WearProjectionPublisher.publishAfterScheduleChange(appContext);
            }
        });
    }

    /**
     * One row per note that has cards, most urgent first.
     *
     * <p>Counted in SQL rather than by loading the cards themselves — the list shows totals, and a
     * note with two hundred cards should cost what a note with three costs. Notes in the trash drop
     * out: their cards stay in the table (restoring a note restores its deck), they just aren't
     * offered for review.
     */
    public void loadDecks(OnDecksLoaded cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            long now = System.currentTimeMillis();
            List<FlashcardDeck> decks = new ArrayList<>();

            // A deck is titled with its note's title, so a shut collection's decks would put those
            // titles on the Flashcards tab regardless of the lock. Locking also deletes the cards
            // themselves (see CollectionLockRepository.lock) — this covers the window where a
            // collection is locked but open in another sense, and any card that outlives it.
            // An open collection is still encrypted at rest, so its titles come back as ciphertext
            // and are decrypted below — hiding the shut ones is only half the job.
            Set<String> lockedIds = NoteCrypto.lockedCollectionIds(db);
            Set<String> hidden = NoteCrypto.hiddenOf(lockedIds);
            List<String> args = new ArrayList<>();
            args.add(String.valueOf(now));
            args.addAll(hidden);
            args.add(String.valueOf(now));

            Cursor c = db.rawQuery(
                    "SELECT n.id, n.title, COUNT(f.id), " +
                            "SUM(CASE WHEN f.next_review <= ? THEN 1 ELSE 0 END), " +
                            "SUM(CASE WHEN f.last_reviewed_at IS NULL THEN 1 ELSE 0 END), " +
                            "MIN(f.next_review), MAX(f.last_reviewed_at), n.collection_id, " +
                            // Only so an unnamed note's deck can be titled with the same dated
                            // fallback the rest of the app shows it under.
                            "n.created_at " +
                            "FROM flashcards f JOIN notes n ON n.id = f.note_id " +
                            "WHERE n.deleted_at IS NULL " +
                            NoteCrypto.hiddenClause(hidden) +
                            "GROUP BY n.id, n.title, n.collection_id, n.created_at " +
                            // Decks with something to do come first; among the rest, whichever comes
                            // back soonest.
                            "ORDER BY (CASE WHEN SUM(CASE WHEN f.next_review <= ? THEN 1 ELSE 0 END) > 0 " +
                            "THEN 0 ELSE 1 END), MIN(f.next_review) ASC",
                    args.toArray(new String[0]));
            try {
                while (c.moveToNext()) {
                    String collectionId = c.isNull(7) ? null : c.getString(7);
                    String title = c.isNull(1)
                            ? "" : NoteCrypto.titleForDisplay(lockedIds, collectionId, c.getString(1));
                    // Decryption failed and the collection has been shut again; a deck the app can
                    // no longer name belongs out of the list, not in it under a blank title.
                    if (title == null) continue;

                    FlashcardDeck deck = new FlashcardDeck();
                    deck.noteId = c.getString(0);
                    deck.noteTitle = title;
                    deck.total = c.getInt(2);
                    deck.due = c.getInt(3);
                    deck.unseen = c.getInt(4);
                    deck.nextReview = c.getLong(5);
                    deck.lastReviewedAt = c.isNull(6) ? null : c.getLong(6);
                    deck.noteCreatedAt = c.getLong(8);
                    decks.add(deck);
                }
            } finally {
                c.close();
            }

            if (cb != null) executors.mainThread(() -> cb.onLoaded(decks));
        });
    }

    /** How many cards a note has — what decides whether it offers "turn into" or "review". */
    public void countForNote(String noteId, OnCounted cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM flashcards WHERE note_id = ?",
                    new String[]{noteId});
            int count = 0;
            try {
                if (c.moveToFirst()) count = c.getInt(0);
            } finally {
                c.close();
            }
            int result = count;
            if (cb != null) executors.mainThread(() -> cb.onCounted(result));
        });
    }

    /** What's waiting to be reviewed: how many cards, across how many notes. */
    public static final class DueSummary {
        public final int cards;
        public final int decks;

        DueSummary(int cards, int decks) {
            this.cards = cards;
            this.decks = decks;
        }

        public boolean isEmpty() {
            return cards == 0;
        }
    }

    /**
     * Counts everything due at {@code now}, for the study reminder.
     *
     * <p>Synchronous, because its one caller is already on a background thread — a
     * {@code androidx.work.Worker} — and going through {@link AppExecutors} there would mean
     * handing the answer to the main thread only to hand it straight back.
     *
     * <p>Locked collections are excluded, and in the reminder's case that exclusion is total:
     * nothing is unlocked in a background worker, so every locked collection is hidden. That is
     * the intended behaviour rather than a limitation — a notification reading "3 cards due" for a
     * collection the user deliberately encrypted would announce, on the lock screen, both that the
     * collection exists and that they have been neglecting it.
     */
    public DueSummary countDueSync(long now) {
        SQLiteDatabase db = appDatabase.getWritableDatabase();
        Set<String> hidden = NoteCrypto.hiddenCollectionIds(db);

        List<String> args = new ArrayList<>();
        args.add(String.valueOf(now));
        args.addAll(hidden);

        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*), COUNT(DISTINCT f.note_id) " +
                        "FROM flashcards f JOIN notes n ON n.id = f.note_id " +
                        "WHERE n.deleted_at IS NULL AND f.next_review <= ? " +
                        NoteCrypto.hiddenClause(hidden),
                args.toArray(new String[0]))) {
            if (!c.moveToFirst()) return new DueSummary(0, 0);
            return new DueSummary(c.getInt(0), c.getInt(1));
        }
    }

    /**
     * Today's due cards as the watch is allowed to see them — the Wear projection's database half.
     *
     * <p>Synchronous for the same reason as {@link #countDueSync}: it is called from the reminder
     * worker and from {@link AppExecutors#diskIO}, both already off the main thread.
     *
     * <p><b>Every locked collection is excluded, open or not</b>, which is deliberately stricter
     * than the rest of the app. Elsewhere the question is "should this appear on screen", and an
     * unlocked collection's notes should; here the question is "should this leave the device", and
     * the answer for anything the user chose to encrypt is no. A watch has no biometric gate, no
     * {@code FLAG_SECURE}, and a Data Layer store that outlives the session that filled it — a
     * projection that shipped while the collection happened to be open would still be sitting there
     * an hour after it was shut again.
     *
     * <p>A useful side effect: because locked collections drop out by collection id rather than by
     * lock state, nothing here can ever hold ciphertext, so unlike {@link #loadDecks} there is no
     * decryption step to forget.
     *
     * <p>The horizon is end-of-day rather than {@code now} — see {@link DueProjection#select}.
     */
    public List<DueCard> dueProjectionSync(long now, TimeZone zone) {
        SQLiteDatabase db = appDatabase.getWritableDatabase();
        Set<String> locked = NoteCrypto.lockedCollectionIds(db);
        long horizon = DueProjection.endOfDayExclusive(now, zone);

        List<String> args = new ArrayList<>();
        args.add(String.valueOf(horizon));
        args.addAll(locked);

        List<DueCard> candidates = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT f.id, f.front, f.back, f.next_review, n.id, n.title, n.created_at " +
                        "FROM flashcards f JOIN notes n ON n.id = f.note_id " +
                        "WHERE n.deleted_at IS NULL AND f.next_review <= ? " +
                        NoteCrypto.excludeCollectionsClause(locked),
                args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                DueCard card = new DueCard();
                card.id = c.getString(0);
                // Markdown on disk, plain text on the wrist. Done here rather than on the watch
                // because this is where the renderer that understands the format lives, and the
                // watch has nothing to draw a bullet with anyway.
                card.front = NoteDocument.toPlainText(c.getString(1));
                card.back = NoteDocument.toPlainText(c.getString(2));
                card.dueAt = c.getLong(3);
                card.noteId = c.getString(4);
                // Resolved here, not on the watch: an untitled note stores an empty title, and the
                // fallback is a localised date string that needs a Context to build. No decryption
                // step — see this method's note on why nothing here can hold ciphertext.
                card.noteTitle = NoteDisplayUtils.resolveTitle(
                        appContext, c.isNull(5) ? null : c.getString(5), c.getLong(6));
                candidates.add(card);
            }
        }

        // Ordering, the cap and the trim are all in :study, where they can be tested without a
        // database — this method's job is the query and the lock rule, and nothing else.
        return DueProjection.select(candidates, horizon);
    }

    /**
     * Deletes a note's whole deck.
     *
     * <p>A hard delete, against the app's soft-delete convention, and deliberately: a card is
     * <em>derived</em> from a Q&amp;A block that the delete doesn't touch. Tombstoning the rows
     * would mean the next sync either resurrects them or — worse — refuses to, leaving a note whose
     * Q&amp;A blocks can never make cards again. What's actually lost is the review history, which
     * is what the confirmation warns about.
     */
    public void deleteForNote(String noteId, Runnable onDeleted) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            int deleted = db.delete("flashcards", "note_id = ?", new String[]{noteId});
            if (onDeleted != null) executors.mainThread(onDeleted);

            // The mirror of the publish in syncFromNote, and the more visible of the two if it is
            // missing: a watch still holding a deleted deck offers cards to review, and answering
            // one sends the phone an id it can no longer find.
            if (deleted > 0) WearProjectionPublisher.publishAfterScheduleChange(appContext);
        });
    }

    /** Persists the card's advanced schedule after an answer given now, on this device. */
    public void recordReview(Flashcard card, boolean correct, Runnable onDone) {
        recordReview(card, correct, System.currentTimeMillis(), onDone);
    }

    /**
     * Persists the card's advanced schedule after an answer given at {@code answeredAt}.
     *
     * <p>The timestamp is a parameter rather than a {@code System.currentTimeMillis()} inside,
     * because an answer from the watch arrives some time after it was given — immediately when
     * tethered, and much later when it wasn't. Anchoring the interval to arrival would push a
     * card answered at 08:00 and delivered at 22:00 fourteen hours further out than the schedule
     * intends, and it would do it silently: every field still looks plausible afterwards, which
     * is what makes this worth a separate overload rather than a caller's discipline.
     */
    public void recordReview(Flashcard card, boolean correct, long answeredAt, Runnable onDone) {
        FlashcardScheduler.applyReview(card, correct, answeredAt);
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("interval", card.interval);
            cv.put("repetitions", card.repetitions);
            cv.put("easiness", card.easiness);
            cv.put("next_review", card.nextReview);
            cv.put("last_reviewed_at", card.lastReviewedAt);
            db.update("flashcards", cv, "id = ?", new String[]{card.id});
            if (onDone != null) executors.mainThread(onDone);

            // After the callback, not before: the review screen should advance at the speed of the
            // database write, not at the speed of a Data Layer round trip. Still on the diskIO
            // thread, which is where publishSync has to be.
            WearProjectionPublisher.publishAfterScheduleChange(appContext);
        });
    }

    // ── Sync helpers (must run on the diskIO executor) ──────────────────────

    /**
     * One card by id, or null if it no longer exists. <b>Blocking — call from a background
     * thread.</b>
     *
     * <p>Null is a normal answer, not an error: an answer can arrive from the watch for a card the
     * phone deleted while the two were apart, and the projection the watch answered from is by
     * then simply out of date. The caller drops it.
     */
    public Flashcard loadByIdSync(String cardId) {
        SQLiteDatabase db = appDatabase.getWritableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id, note_id, source_segment_id, front, back, interval, repetitions, " +
                        "easiness, next_review, last_reviewed_at " +
                        "FROM flashcards WHERE id = ?",
                new String[]{cardId})) {
            if (!c.moveToFirst()) return null;
            Flashcard card = new Flashcard();
            card.id = c.getString(0);
            card.noteId = c.getString(1);
            card.sourceSegmentId = c.getString(2);
            card.front = c.isNull(3) ? "" : c.getString(3);
            card.back = c.isNull(4) ? "" : c.getString(4);
            card.interval = c.getInt(5);
            card.repetitions = c.getInt(6);
            card.easiness = c.getDouble(7);
            card.nextReview = c.getLong(8);
            card.lastReviewedAt = c.isNull(9) ? null : c.getLong(9);
            return card;
        }
    }

    private Map<String, Flashcard> loadBySegmentIdSync(SQLiteDatabase db, String noteId) {
        Map<String, Flashcard> cards = new HashMap<>();
        Cursor c = db.rawQuery(
                "SELECT id, note_id, source_segment_id, front, back, interval, repetitions, " +
                        "easiness, next_review, last_reviewed_at " +
                        "FROM flashcards WHERE note_id = ? AND source_segment_id IS NOT NULL",
                new String[]{noteId});
        try {
            while (c.moveToNext()) {
                Flashcard card = new Flashcard();
                card.id = c.getString(0);
                card.noteId = c.getString(1);
                card.sourceSegmentId = c.getString(2);
                card.front = c.isNull(3) ? "" : c.getString(3);
                card.back = c.isNull(4) ? "" : c.getString(4);
                card.interval = c.getInt(5);
                card.repetitions = c.getInt(6);
                card.easiness = c.getDouble(7);
                card.nextReview = c.getLong(8);
                card.lastReviewedAt = c.isNull(9) ? null : c.getLong(9);
                cards.put(card.sourceSegmentId, card);
            }
        } finally {
            c.close();
        }
        return cards;
    }

    private static ContentValues toValues(Flashcard card) {
        ContentValues cv = new ContentValues();
        cv.put("id", card.id);
        cv.put("note_id", card.noteId);
        cv.put("source_segment_id", card.sourceSegmentId);
        cv.put("front", card.front);
        cv.put("back", card.back);
        cv.put("interval", card.interval);
        cv.put("repetitions", card.repetitions);
        cv.put("easiness", card.easiness);
        cv.put("next_review", card.nextReview);
        cv.put("last_reviewed_at", card.lastReviewedAt);
        return cv;
    }
}
