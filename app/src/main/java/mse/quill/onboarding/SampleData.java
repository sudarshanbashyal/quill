package mse.quill.onboarding;

import android.content.Context;
import android.graphics.PointF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import mse.quill.data.AppDatabase;
import mse.quill.data.AppExecutors;
import mse.quill.data.CollectionRepository;
import mse.quill.data.FlashcardRepository;
import mse.quill.data.NoteRepository;
import mse.quill.data.QuizRepository;
import mse.quill.data.StrokeRepository;
import mse.quill.data.WhiteboardRepository;
import mse.quill.data.model.Stroke;
import mse.quill.data.model.Whiteboard;
import mse.quill.data.serialization.NoteDocument;
import mse.quill.ui.notes.editor.model.NoteSegment;
import mse.quill.ui.whiteboard.WhiteboardView;
import mse.quill.util.ColorUtils;
import mse.quill.widget.WidgetUpdater;

/**
 * Fills a brand-new Quill with something to look at: a collection, three notes (one of them
 * pinned), a deck of flashcards, a quiz and a whiteboard.
 *
 * <p><b>Written through the repositories, not with SQL of its own.</b> Sample content that took a
 * private path into the database would be the one content in the app that skips encryption for a
 * locked collection, skips the search index, skips the whiteboard link table and skips every
 * widget and watch refresh — and it would keep skipping whatever is added next. Going through the
 * ordinary calls costs a few more lines here and nothing in maintenance.
 *
 * <p><b>Ordering comes from the disk thread, which is one thread.</b> Every repository call queues
 * onto {@link AppExecutors#diskIO}, so calls issued in order run in order: the note row exists
 * before the save that fills it, the board exists before the note that embeds it, and the final
 * marker task below cannot run until everything ahead of it has. That is also why this needs no
 * locking around {@link Summary} — every field is written from a repository callback, and those
 * all land on the main thread.
 */
public final class SampleData {

    /** What was actually created, for the screen that tells the user about it. */
    public static final class Summary {
        public int collections;
        public int notes;
        public int pinnedNotes;
        public int flashcards;
        public int quizzes;
        public int whiteboards;

        /** Named so the report can say which collection and which note, rather than just counts. */
        public String collectionName;
        public String pinnedNoteTitle;
    }

    /** Delivered on the main thread once every row is written. */
    public interface Callback {
        void onSeeded(Summary summary);
    }

    private SampleData() {}

    /**
     * Creates the sample content. Call from the main thread; the work happens off it.
     *
     * <p>There is no failure path to report. Every step is a local insert that either happens or
     * throws, and a half-written sample is still a usable app — so the report simply says what is
     * there, which is what the caller shows.
     */
    public static void seed(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        Summary summary = new Summary();

        // The one id the caller can't mint for itself, so this step is the only nested callback.
        new CollectionRepository(appContext).createCollection(
                SampleContent.COLLECTION_NAME,
                ColorUtils.randomPaletteColor(appContext),
                collectionId -> {
                    summary.collections = 1;
                    summary.collectionName = SampleContent.COLLECTION_NAME;
                    seedRest(appContext, collectionId, summary, callback);
                });
    }

    private static void seedRest(Context appContext, String collectionId,
                                 Summary summary, Callback callback) {
        AppExecutors executors = AppExecutors.getInstance();
        NoteRepository notes = new NoteRepository(appContext);

        // Minted here so the welcome note's embed line can point at a board that does not exist
        // yet — the insert below is queued first, so by the time the note is saved it does.
        String whiteboardId = UUID.randomUUID().toString();
        executors.diskIO(() -> {
            insertSketch(appContext, whiteboardId);
            // Counted on the main thread like every other field, so the summary has exactly one
            // writer thread and needs no locking of its own.
            executors.mainThread(() -> summary.whiteboards = 1);
        });

        String welcomeId = NoteRepository.newNoteId();
        List<NoteSegment> welcome = parse(
                String.format(SampleContent.WELCOME_MARKDOWN, whiteboardId));
        notes.createNote(welcomeId, SampleContent.WELCOME_TITLE, collectionId,
                () -> summary.notes++);
        notes.saveNote(welcomeId, SampleContent.WELCOME_TITLE, welcome, (Runnable) null);
        notes.pinNote(welcomeId, new NoteRepository.OnPinResult() {
            @Override public void onPinned() {
                summary.pinnedNotes = 1;
                summary.pinnedNoteTitle = SampleContent.WELCOME_TITLE;
            }

            // Unreachable on a first install — there is nothing else to have filled the three
            // pinned slots — but the report is built from what happened, not from what was meant
            // to happen, so a note that didn't get pinned isn't announced as pinned.
            @Override public void onLimitReached() {}
        });

        String techniquesId = NoteRepository.newNoteId();
        List<NoteSegment> techniques = parse(SampleContent.TECHNIQUES_MARKDOWN);
        notes.createNote(techniquesId, SampleContent.TECHNIQUES_TITLE, collectionId,
                () -> summary.notes++);
        notes.saveNote(techniquesId, SampleContent.TECHNIQUES_TITLE, techniques, (Runnable) null);

        // The same segment objects the note was saved from, so the cards carry the block ids that
        // were just written into the document — pass a re-parse and every card would be orphaned
        // from its block by the next edit.
        new FlashcardRepository(appContext).syncFromNote(techniquesId, techniques,
                deck -> summary.flashcards = deck.size());
        new QuizRepository(appContext).ensureForNote(techniquesId, quiz -> summary.quizzes = 1);

        String scratchId = NoteRepository.newNoteId();
        notes.createNote(scratchId, SampleContent.SCRATCH_TITLE, null, () -> summary.notes++);
        notes.saveNote(scratchId, SampleContent.SCRATCH_TITLE,
                parse(SampleContent.SCRATCH_MARKDOWN), (Runnable) null);

        // Last in the queue, so it runs last. The widgets are pushed from here rather than trusted
        // to the individual writes above: a first-run user has no widget on their home screen yet,
        // but one added five seconds later reads whatever these rows say.
        executors.diskIO(() -> {
            WidgetUpdater.notifyAllChanged(appContext);
            executors.mainThread(() -> callback.onSeeded(summary));
        });
    }

    private static List<NoteSegment> parse(String markdown) {
        return NoteDocument.fromMarkdown(markdown, Collections.emptyMap());
    }

    // ---------- The sketch ----------

    /** Ink colours: the axes in the app's near-black, the curves in the brand purple. */
    private static final int AXIS_COLOUR = 0xFF2D2E37;
    private static final int CURVE_COLOUR = 0xFF7C6FEA;

    /** Canvas coordinates. The board opens centred on its ink, so only the shape matters here. */
    private static final float LEFT = 200f;
    private static final float RIGHT = 900f;
    private static final float TOP = 160f;
    private static final float BASE = 560f;
    private static final float REVIEW_X = 560f;

    /**
     * Draws the forgetting curve: memory decaying, a review, then a slower decay.
     *
     * <p>A drawing rather than an empty board because an empty one says nothing about what the
     * feature is for, and it also leaves Home's whiteboard row and the widget showing a blank
     * rectangle. This one earns its place twice — it is the subject of the note it is embedded in.
     *
     * <p>Blocking: strokes carry a foreign key onto the board, so the row goes in first and both
     * writes are synchronous. Call from the disk thread.
     */
    private static void insertSketch(Context appContext, String whiteboardId) {
        long now = System.currentTimeMillis();

        Whiteboard board = new Whiteboard();
        board.id = whiteboardId;
        board.noteId = null;          // standalone: it belongs to Home, and is embedded by link
        board.title = SampleContent.WHITEBOARD_TITLE;
        board.createdAt = now;
        board.updatedAt = now;
        board.background = WhiteboardView.BACKGROUND_DOTS;
        new WhiteboardRepository(appContext).insertSync(board);

        StrokeRepository strokes = new StrokeRepository(AppDatabase.getInstance(appContext));
        strokes.insertStrokeSync(stroke(whiteboardId, AXIS_COLOUR, 6f, now,
                line(LEFT, TOP, LEFT, BASE)));
        strokes.insertStrokeSync(stroke(whiteboardId, AXIS_COLOUR, 6f, now + 1,
                line(LEFT, BASE, RIGHT, BASE)));
        strokes.insertStrokeSync(stroke(whiteboardId, CURVE_COLOUR, 7f, now + 2,
                decay(LEFT, REVIEW_X, TOP + 20f, BASE - 40f, 3.2f)));
        // The review itself: straight back up to where it started.
        strokes.insertStrokeSync(stroke(whiteboardId, CURVE_COLOUR, 7f, now + 3,
                line(REVIEW_X, BASE - 40f, REVIEW_X, TOP + 20f)));
        // Shallower, because a reviewed memory decays more slowly — the whole point of the curve.
        strokes.insertStrokeSync(stroke(whiteboardId, CURVE_COLOUR, 7f, now + 4,
                decay(REVIEW_X, RIGHT, TOP + 20f, BASE - 140f, 1.4f)));
    }

    /** Points along an exponential decay from {@code fromY} towards {@code toY}. */
    private static List<PointF> decay(float fromX, float toX, float fromY, float toY, float rate) {
        List<PointF> points = new ArrayList<>();
        int steps = 28;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float fall = 1f - (float) Math.exp(-rate * t);
            points.add(new PointF(fromX + (toX - fromX) * t, fromY + (toY - fromY) * fall));
        }
        return points;
    }

    private static List<PointF> line(float fromX, float fromY, float toX, float toY) {
        List<PointF> points = new ArrayList<>();
        points.add(new PointF(fromX, fromY));
        points.add(new PointF(toX, toY));
        return points;
    }

    /** One stroke over a run of points, in the shape {@code StrokeRepository} expects. */
    private static Stroke stroke(String whiteboardId, int colour, float width, long createdAt,
                                 List<PointF> points) {
        Stroke stroke = new Stroke();
        stroke.id = UUID.randomUUID().toString();
        stroke.whiteboardId = whiteboardId;
        // The same author id the canvas gives a stroke drawn by hand on this device.
        stroke.authorId = "local-user";
        stroke.tool = WhiteboardView.TOOL_PEN;
        stroke.color = colour;
        stroke.width = width;
        stroke.points = points;
        stroke.createdAt = createdAt;
        return stroke;
    }
}
