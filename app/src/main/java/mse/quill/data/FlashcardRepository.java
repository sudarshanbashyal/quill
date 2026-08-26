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

import mse.quill.data.DataChangeNotifier.Change;
import mse.quill.data.model.DueCard;
import mse.quill.data.model.Flashcard;
import mse.quill.data.model.FlashcardDeck;
import mse.quill.data.serialization.MarkdownSerializer;
import mse.quill.data.serialization.NoteDocument;
import mse.quill.data.model.NoteSegment;
import mse.quill.data.model.QaSegment;
import mse.quill.util.NoteDisplayUtils;
import mse.quill.data.wear.WearProjectionPublisher;

/**
 * The flashcards generated from a note's Q&amp;A blocks, and their review schedule.
 *
 * <p>Generation is a sync, not an import: a block keeps its id in the note's Markdown, so re-running
 * it over an edited note updates that block's card text and leaves its SM-2 columns alone. A card
 * whose block has since been deleted is deliberately <em>not</em> removed — someone's review history
 * shouldn't evaporate because a note got tidied up — it simply stops appearing in the note's deck.
 *
 * <p>"Stops appearing" is a stamp on the row, {@code orphaned_at}, not an absence from the results
 * of one query. It used to be the latter, and the two halves of the app then disagreed: the review
 * screen showed only cards the note could still produce, while the decks list counted rows, so
 * emptying an answer left a deck reading "1 due" that opened onto "No cards yet". Every count of
 * what there is to review filters on the stamp; only {@link #loadBySegmentIdSync} ignores it, since
 * that is what has to find an orphaned card again in order to revive it.
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
                // Whatever this note's cards used to come from and no longer do. Inside the same
                // transaction as the writes above, so the deck and the counts of it can never
                // disagree about which cards exist.
                projectionChanged |= markOrphansSync(db, noteId, reviewable);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            DataChangeNotifier.getInstance().notifyChanged(Change.FLASHCARDS);

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
     * Brings {@code orphaned_at} in line with what the note's Q&amp;A blocks can currently produce,
     * and reports whether anything moved.
     *
     * <p>Both directions matter. A card is stamped when its block is gone or has had a half
     * emptied — the state the user is in halfway through rewriting an answer — and unstamped the
     * moment that block can make a card again, with its schedule untouched, so a card is not
     * punished for the minute its answer field spent blank.
     *
     * <p>Matched on {@code source_segment_id} rather than on card ids, so the same call works for
     * a caller that has just built the deck and for one that only has the note's segments. Cards
     * with no {@code source_segment_id} — rows from before that column existed — are stamped too:
     * a sync can never match one to a block, so they were already invisible for review while still
     * being counted, which is the same lie by a different route.
     *
     * <p>Callers must already be inside a transaction on {@code db}.
     */
    static boolean markOrphansSync(SQLiteDatabase db, String noteId, List<QaSegment> reviewable) {
        List<String> liveIds = new ArrayList<>();
        for (QaSegment qa : reviewable) {
            if (qa.id != null) liveIds.add(qa.id);
        }

        ContentValues stamp = new ContentValues();
        stamp.put("orphaned_at", System.currentTimeMillis());

        if (liveIds.isEmpty()) {
            // No blocks left to keep anything alive, so every one of this note's cards is orphaned
            // — and nothing can be revived, which is why this doesn't fall through to the pair
            // below (an empty IN list is not valid SQL anyway).
            return db.update("flashcards", stamp,
                    "note_id = ? AND orphaned_at IS NULL", new String[]{noteId}) > 0;
        }

        // Both statements match on the same list, so they share one argument array.
        String liveList = placeholders(liveIds.size());
        List<String> args = new ArrayList<>();
        args.add(noteId);
        args.addAll(liveIds);
        String[] argArray = args.toArray(new String[0]);

        int stamped = db.update("flashcards", stamp,
                "note_id = ? AND orphaned_at IS NULL "
                        + "AND (source_segment_id IS NULL OR source_segment_id NOT IN (" + liveList + "))",
                argArray);

        ContentValues revive = new ContentValues();
        revive.putNull("orphaned_at");
        int revived = db.update("flashcards", revive,
                "note_id = ? AND orphaned_at IS NOT NULL AND front <> '' "
                        + "AND source_segment_id IN (" + liveList + ")",
                argArray);

        return stamped + revived > 0;
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append('?');
        }
        return sb.toString();
    }

    /**
     * Takes a note's cards out of circulation for a lock, keeping everything that isn't its text.
     *
     * <p>This used to be a delete, and the delete was half right. {@code front} and {@code back} are
     * copies of the note's Q&amp;A blocks in plaintext columns, so leaving them behind a locked
     * collection would keep a readable copy of the note in a table the lock doesn't reach — that
     * part was never negotiable. But the SM-2 columns are not content: an interval, a repetition
     * count and an easiness factor say nothing about what the note contains, and throwing them away
     * cost the user the only thing in a deck that can't be regenerated. Re-creating cards later was
     * always possible — {@link #syncFromNote} rebuilds them from the blocks — what was lost was
     * everything Quill knew about how well they were known.
     *
     * <p>So the row stays, its text is blanked, and it is stamped orphaned so every "what is there
     * to review" query already leaves it out. The next sync of that note refills the text from its
     * blocks and clears the stamp, with the schedule untouched.
     *
     * <p>The revive in {@link #markOrphansSync} requires non-empty text for exactly this reason: a
     * plain save must not bring a blanked card back before something has put its question back in
     * it.
     */
    static void suspendForLockSync(SQLiteDatabase db, String noteId) {
        ContentValues cv = new ContentValues();
        cv.put("front", "");
        cv.put("back", "");
        cv.put("orphaned_at", System.currentTimeMillis());
        db.update("flashcards", cv, "note_id = ?", new String[]{noteId});
    }

    /**
     * The save path's entry point: re-stamps a note's cards without ever creating one.
     *
     * <p>{@link #syncFromNote} can't be used here. It is the thing that <em>makes</em> a deck, so
     * running it on every save would turn writing a Q&amp;A block into silently generating
     * flashcards nobody asked for. This only ever moves the stamp on rows that already exist, which
     * means a note with no deck costs one indexed count and nothing else.
     *
     * <p>Called from inside {@code NoteRepository.saveNote}'s transaction so the decks list is
     * right as soon as the note is, rather than only after the deck screen has next been opened —
     * which is the whole point, since the list is what was showing the wrong number.
     */
    public static boolean markOrphansOnSaveSync(SQLiteDatabase db, String noteId,
                                                List<NoteSegment> segments) {
        return markOrphansSync(db, noteId, reviewableQa(segments));
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
            List<FlashcardDeck> decks = loadDecksSync();
            if (cb != null) executors.mainThread(() -> cb.onLoaded(decks));
        });
    }

    /** Synchronous form of {@link #loadDecks}, for callers already off the main thread. */
    public List<FlashcardDeck> loadDecksSync() {
        return loadDecksSync(false);
    }

    /**
     * The decks a home-screen widget is allowed to show — <b>every locked collection is excluded,
     * open or not</b>. See {@code NoteRepository.loadPinnedNotesForWidgetSync} for the reasoning;
     * a deck is titled with its note's title, so this is what keeps those titles off the home
     * screen for any collection the user encrypted.
     */
    public List<FlashcardDeck> loadDecksForWidgetSync() {
        return loadDecksSync(true);
    }

    private List<FlashcardDeck> loadDecksSync(boolean excludeAllLocked) {
            SQLiteDatabase db = appDatabase.getReadableDatabase();
            long now = System.currentTimeMillis();
            List<FlashcardDeck> decks = new ArrayList<>();

            // A deck is titled with its note's title, so a shut collection's decks would put those
            // titles on the Flashcards tab regardless of the lock. Locking also deletes the cards
            // themselves (see CollectionLockRepository.lock) — this covers the window where a
            // collection is locked but open in another sense, and any card that outlives it.
            // An open collection is still encrypted at rest, so its titles come back as ciphertext
            // and are decrypted below — hiding the shut ones is only half the job.
            Set<String> lockedIds = NoteCrypto.lockedCollectionIds(db);
            // Either the collections shut right now, or — for the widget — every one that is
            // encrypted at rest. Which of the two is the only difference between the two callers.
            Set<String> excluded = excludeAllLocked ? lockedIds : NoteCrypto.hiddenOf(lockedIds);
            List<String> args = new ArrayList<>();
            args.add(String.valueOf(now));
            args.addAll(excluded);
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
                            // orphaned_at drops the cards the note can no longer produce. Because
                            // the JOIN feeds a GROUP BY, a note whose every card is orphaned falls
                            // out of the list entirely rather than sitting there as an empty deck
                            // — which is what the review screen has always shown for it.
                            "WHERE n.deleted_at IS NULL AND f.orphaned_at IS NULL " +
                            NoteCrypto.excludeCollectionsClause(excluded) +
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

            return decks;
    }

    /**
     * Every card due at {@code now}, across every note, soonest first — the global review session.
     *
     * <p>The same exclusions the decks list applies, for the same reasons: trashed notes, orphaned
     * cards, and any collection that is shut. Full {@link Flashcard} rows rather than the previews
     * {@link #loadDueCardsSync} hands the widget, because this deck gets graded and the SM-2
     * columns have to make the round trip.
     *
     * <p>No syncing. A per-note session reconciles its note's blocks against its cards on the way
     * in, which is right when you have arrived from that note; doing it here would mean reading and
     * re-parsing every note in the app to open one review screen. The cards are already kept in
     * step by every save.
     */
    public void loadDueAcrossNotes(long now, OnDeckLoaded cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getReadableDatabase();
            Set<String> hidden = NoteCrypto.hiddenCollectionIds(db);

            List<String> args = new ArrayList<>();
            args.add(String.valueOf(now));
            args.addAll(hidden);

            List<Flashcard> due = new ArrayList<>();
            try (Cursor c = db.rawQuery(
                    "SELECT f.id, f.note_id, f.source_segment_id, f.front, f.back, f.interval, "
                            + "f.repetitions, f.easiness, f.next_review, f.last_reviewed_at "
                            + "FROM flashcards f JOIN notes n ON n.id = f.note_id "
                            + "WHERE n.deleted_at IS NULL AND f.orphaned_at IS NULL "
                            + "AND f.next_review <= ? "
                            + NoteCrypto.hiddenClause(hidden)
                            + "ORDER BY f.next_review ASC",
                    args.toArray(new String[0]))) {
                while (c.moveToNext()) due.add(readCard(c));
            }
            if (cb != null) executors.mainThread(() -> cb.onLoaded(due));
        });
    }

    /** The ten columns every full-card read selects, in order. */
    private static Flashcard readCard(Cursor c) {
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

    /** How many cards a note has — what decides whether it offers "turn into" or "review". */
    public void countForNote(String noteId, OnCounted cb) {
        executors.diskIO(() -> {
            SQLiteDatabase db = appDatabase.getReadableDatabase();
            Cursor c = db.rawQuery(
                    "SELECT COUNT(*) FROM flashcards WHERE note_id = ? AND orphaned_at IS NULL",
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
        SQLiteDatabase db = appDatabase.getReadableDatabase();
        Set<String> hidden = NoteCrypto.hiddenCollectionIds(db);

        List<String> args = new ArrayList<>();
        args.add(String.valueOf(now));
        args.addAll(hidden);

        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*), COUNT(DISTINCT f.note_id) " +
                        "FROM flashcards f JOIN notes n ON n.id = f.note_id " +
                        "WHERE n.deleted_at IS NULL AND f.orphaned_at IS NULL "
                        + "AND f.next_review <= ? " +
                        NoteCrypto.hiddenClause(hidden),
                args.toArray(new String[0]))) {
            if (!c.moveToFirst()) return new DueSummary(0, 0);
            return new DueSummary(c.getInt(0), c.getInt(1));
        }
    }

    /** A due card's front, for the flashcards widget's due-now list — just enough to render a
     *  row and navigate to its note's deck on tap. Markdown, same as {@link Flashcard#front};
     *  the widget converts it to plain text at render time. */
    public static final class DueCardPreview {
        public final String id;
        public final String noteId;
        public final String front;

        DueCardPreview(String id, String noteId, String front) {
            this.id = id;
            this.noteId = noteId;
            this.front = front;
        }
    }

    /**
     * The next {@code limit} cards due at {@code now}, soonest first — for the flashcards
     * widget's due-now list, which (like {@link #countDueSync}) has to run synchronously from a
     * {@code RemoteViewsFactory} rather than through {@link AppExecutors}. Locked collections are
     * excluded the same way {@link #countDueSync} excludes them.
     */
    public List<DueCardPreview> loadDueCardsSync(long now, int limit) {
        return loadDueCardsSync(now, limit, false);
    }

    /**
     * The due cards a home-screen widget is allowed to show — <b>every locked collection is
     * excluded, open or not</b>, the same rule {@link #dueProjectionSync} applies for the watch
     * and for the same reason: neither surface has a session or a gate.
     */
    public List<DueCardPreview> loadDueCardsForWidgetSync(long now, int limit) {
        return loadDueCardsSync(now, limit, true);
    }

    private List<DueCardPreview> loadDueCardsSync(long now, int limit, boolean excludeAllLocked) {
        SQLiteDatabase db = appDatabase.getReadableDatabase();
        Set<String> excluded = excludeAllLocked
                ? NoteCrypto.lockedCollectionIds(db) : NoteCrypto.hiddenCollectionIds(db);

        List<String> args = new ArrayList<>();
        args.add(String.valueOf(now));
        args.addAll(excluded);
        args.add(String.valueOf(limit));

        List<DueCardPreview> cards = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT f.id, f.note_id, f.front " +
                        "FROM flashcards f JOIN notes n ON n.id = f.note_id " +
                        "WHERE n.deleted_at IS NULL AND f.orphaned_at IS NULL "
                        + "AND f.next_review <= ? " +
                        NoteCrypto.excludeCollectionsClause(excluded) +
                        "ORDER BY f.next_review ASC LIMIT ?",
                args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                cards.add(new DueCardPreview(c.getString(0), c.getString(1),
                        c.isNull(2) ? "" : c.getString(2)));
            }
        }
        return cards;
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
        SQLiteDatabase db = appDatabase.getReadableDatabase();
        Set<String> locked = NoteCrypto.lockedCollectionIds(db);
        long horizon = DueProjection.endOfDayExclusive(now, zone);

        List<String> args = new ArrayList<>();
        args.add(String.valueOf(horizon));
        args.addAll(locked);

        List<DueCard> candidates = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT f.id, f.front, f.back, f.next_review, n.id, n.title, n.created_at " +
                        "FROM flashcards f JOIN notes n ON n.id = f.note_id " +
                        "WHERE n.deleted_at IS NULL AND f.orphaned_at IS NULL "
                        + "AND f.next_review <= ? " +
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
            if (deleted > 0) DataChangeNotifier.getInstance().notifyChanged(Change.FLASHCARDS);
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
            DataChangeNotifier.getInstance().notifyChanged(Change.FLASHCARDS);
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
        SQLiteDatabase db = appDatabase.getReadableDatabase();
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
