package mse.quill.ui.whiteboard;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.inputmethod.InputMethodManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.fragment.app.Fragment;

import mse.quill.R;
import mse.quill.data.StrokeRepository;
import mse.quill.data.AppExecutors;
import mse.quill.data.WhiteboardRepository;
import mse.quill.data.WhiteboardTextRepository;
import mse.quill.data.model.Stroke;
import mse.quill.data.model.Whiteboard;
import mse.quill.data.model.WhiteboardText;
import mse.quill.export.StoragePermission;
import mse.quill.util.NoteDisplayUtils;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import mse.quill.util.PipAware;

/**
 * WhiteboardFragment  (SINGLE-DEVICE VERSION — no networking)
 *
 * WHAT THIS FILE DOES:
 * Hosts the WhiteboardView inside the app's single-Activity Navigation
 * graph, wires up the toolbar (tools, colors, widths, undo, clear, export),
 * and persists strokes to the local SQLite database via StrokeRepository.
 *
 * Networking (Wi-Fi Direct / StrokeServer / StrokeClient) is intentionally
 * left out of this version — everything works fully offline on one device.
 * When you're ready to add collaboration, you re-introduce WiFiDirectManager
 * and call server.broadcast()/client.sendStroke() inside onStrokeComplete().
 *
 * Navigation args expected (set in nav_graph.xml, passed via Bundle):
 *   "note_id"        — String, optional. The parent note, when the board was opened from one.
 *                       Null for the standalone boards Home's Whiteboards section lists.
 *   "whiteboard_id"  — String, optional. Pass this to reopen an existing whiteboard;
 *                       omit it to create a new one.
 */
public class WhiteboardFragment extends Fragment
        implements WhiteboardView.StrokeListener, PipAware,
                   WhiteboardCollabController.Host, WhiteboardExportController.Host {

    private static final String TAG = "WhiteboardFragment";

    public static final String ARG_NOTE_ID = "note_id";
    public static final String ARG_WHITEBOARD_ID = "whiteboard_id";
    /** See the argument's own note in nav_graph.xml. */
    public static final String ARG_CREATED_NOW = "created_now";
    /** A session token to join the moment this board opens, from a scan or a {@code quill://}
     *  link — see {@link mse.quill.collab.SessionCode}. Null for an ordinary board. */
    public static final String ARG_JOIN_TOKEN = "join_token";

    // ── Views ─────────────────────────────────────────────────────────────────
    private WhiteboardView whiteboardView;
    private EditText       titleInput;
    /** Whether this board came into existence for this visit — see {@link #discardIfNeverUsed}. */
    private boolean        createdThisSession;
    private ImageButton    btnPen, btnEraser, btnHighlighter, btnMove, btnText;
    private EditText       textEditor;
    private ImageButton    btnColorBlack, btnColorRed, btnColorBlue, btnColorGreen, btnColorYellow;
    private ImageButton    btnWidthThin, btnWidthMedium, btnWidthThick, btnWidthExtraThick;
    private ImageButton    btnCentre, btnUndo, btnClear, btnExport, btnToggleTools;
    private ImageButton    btnBackground, btnCollab, btnPip;
    private View           leftSidebar;
    private View           topToolbar;
    /** Whatever the tool rail's own visibility was before PIP hid it, so coming back out of PIP
     *  restores it rather than always forcing it open. */
    private boolean        sidebarVisibleBeforePip;

    // ── Live collaboration (Epic C) ──────────────────────────────────────────
    /** Everything networked about this screen — hosting, joining, the roster, incoming messages.
     *  This fragment owns the canvas and the database; the controller owns the wire, and the two
     *  meet at {@link WhiteboardCanvas}. */
    private WhiteboardCollabController collab;
    /** The whole roster, as one count in the top bar. */
    private MaterialButton collabPeople;
    /** The names behind that count, exactly as the controller last handed them over — this screen
     *  never derives its own idea of who is in the session. */
    private List<String> collabRoster = new ArrayList<>();
    /** Registered here rather than in onCreate because a launcher has to exist before the
     *  fragment reaches STARTED. Only ever climbed on API 26-28. */
    private final StoragePermission storagePermission = new StoragePermission(this);
    /** Both ways this board leaves Quill — see {@link WhiteboardExportController}. */
    private WhiteboardExportController export;

    // ── Data ──────────────────────────────────────────────────────────────────
    private StrokeRepository     strokeRepo;
    private WhiteboardTextRepository textRepo;
    private final AppExecutors executors = AppExecutors.getInstance();
    private WhiteboardRepository whiteboardRepo;
    private String        whiteboardId;
    private String        noteId;
    private Stroke        lastStroke; // enables single-level undo
    /** Mirrors what the rail has selected, so text can be given the same colour and scale. */
    private int   currentColor = android.graphics.Color.BLACK;
    private float currentWidth = 7f;
    /** The name as it was loaded, so leaving without renaming writes nothing. */
    private String loadedTitle;
    private float pendingTextX;
    private float pendingTextY;
    /** A 7px stroke writes ~28px text: legible next to the line the same setting draws. */
    private static final float TEXT_SIZE_PER_STROKE_WIDTH = 4f;
    /**
     * What undo walks back through, newest first. Holds both strokes and text items because a
     * board is one sequence of things you added — undoing should take back the last of them,
     * whichever kind it was.
     */
    private final Deque<Undoable> undoStack = new ArrayDeque<>();

    /** The one toast this screen ever has up — see {@link #showTransientMessage}. */
    private Toast transientToast;

    /** An id plus what it is, so undo knows which view call and which table to use. */
    private static final class Undoable {
        final String id;
        final boolean text;
        final long createdAt;
        Undoable(String id, boolean text, long createdAt) {
            this.id = id;
            this.text = text;
            this.createdAt = createdAt;
        }
    }
    // ── Fragment lifecycle ────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Read navigation arguments passed by whoever opened this screen
        Bundle args  = getArguments();
        noteId       = args != null ? args.getString(ARG_NOTE_ID)       : null;
        whiteboardId = args != null ? args.getString(ARG_WHITEBOARD_ID) : null;
        // Either the caller says so, or there was no id to open — which can only mean this screen
        // is about to mint one below.
        createdThisSession = whiteboardId == null
                || (args != null && args.getBoolean(ARG_CREATED_NOW, false));

        // 2. Repositories resolve the database singleton themselves — see StrokeRepository.
        strokeRepo     = new StrokeRepository(requireContext());
        textRepo       = new WhiteboardTextRepository(requireContext());
        whiteboardRepo = new WhiteboardRepository(requireContext());

        // 3. If no whiteboard_id was passed, this is a brand-new whiteboard —
        //    generate an id and insert a row into the `whiteboards` table.
        if (whiteboardId == null) {
            whiteboardId    = UUID.randomUUID().toString();
            Whiteboard wb   = new Whiteboard();
            wb.id           = whiteboardId;
            wb.noteId       = noteId;
            wb.createdAt    = System.currentTimeMillis();
            wb.updatedAt    = wb.createdAt;
            wb.background   = WhiteboardPreferences.defaultBackground(requireContext());
            // `strokes` has a foreign key onto this row, so it has to exist before the first
            // stroke insert — but that no longer needs a blocking write on the main thread to
            // arrange. Every write on this screen now goes through AppExecutors' single disk
            // thread, which runs them in submission order, so queueing this one first is enough.
            executors.diskIO(() -> whiteboardRepo.insertSync(wb));
        }

        // 4. Built here rather than in onViewCreated because it needs the settled whiteboardId,
        //    and because a join arriving from Home can reach it before there is a view at all.
        collab = new WhiteboardCollabController(this, whiteboardId, this);
        export = new WhiteboardExportController(this, this, storagePermission);
    }

    /**
     * Bumps the board's updated_at so Home's Whiteboards section sorts by real recency. Called on
     * every canvas change rather than on exit, since the fragment can go away without onStop work
     * completing.
     */
    private void touchWhiteboard() {
        final String id = whiteboardId;
        final long now = System.currentTimeMillis();
        executors.diskIO(() -> whiteboardRepo.touchSync(id, now));
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Inflate fragment_whiteboard.xml into a View object
        return inflater.inflate(R.layout.fragment_whiteboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        bindViews(view);          // find all UI elements by id
        setupToolbar();           // wire up click listeners

        // Intercept system/gesture back the same way as the in-app back button, so a live
        // collab session can't be dropped silently through either exit path.
        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(), new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        collab.attemptExit();
                    }
                });
        whiteboardView.setStrokeListener(this); // get notified when a stroke finishes
        whiteboardView.setTextPlacementListener(this::beginText);
        textEditor.setOnEditorActionListener((v, actionId, e) -> {
            commitText();
            return true;
        });

        // Only on a first creation: the arguments survive a rotation, and rejoining a session this
        // screen is already in would start a second one behind the first.
        String joinToken = getArguments() == null ? null : getArguments().getString(ARG_JOIN_TOKEN);
        if (savedInstanceState == null && joinToken != null) {
            // joinWithToken climbs the permission ladder itself; a refusal leaves an empty board,
            // which discardIfNeverUsed then takes away again.
            collab.joinWithToken(joinToken);
        }

        // Load any strokes already saved for this whiteboard (e.g. reopening a note)
        // Runs on a background thread because SQLite reads should not block the UI thread.
        executors.diskIO(() -> {
            List<Stroke> existing = strokeRepo.getByWhiteboardSync(whiteboardId);
            List<WhiteboardText> existingText = textRepo.getByWhiteboardSync(whiteboardId);
            executors.mainThread(() -> {
                if (whiteboardView == null) return;
                whiteboardView.loadTexts(existingText);
                whiteboardView.loadStrokes(existing);

                // Rebuild the undo stack in the order things were added — strokes and text
                // interleaved by time — so undo starts from the most recent either way.
                undoStack.clear();
                List<Undoable> all = new ArrayList<>();
                for (Stroke s : existing) all.add(new Undoable(s.id, false, s.createdAt));
                for (WhiteboardText t : existingText) all.add(new Undoable(t.id, true, t.createdAt));
                all.sort((a, b) -> Long.compare(a.createdAt, b.createdAt));
                for (Undoable u : all) undoStack.push(u);
            });
        });
    }

    /**
     * Leaves if the board is no longer ours to show — deleted from Home behind this screen, or
     * belonging to a note whose collection shut while the app was away.
     *
     * <p>The second case is why this is on resume rather than only on load: leaving the app
     * re-locks every open collection ({@code MainActivity.onStop}), and a board reached through a
     * locked note would otherwise still be sitting here, fully drawn, when the user came back.
     */
    @Override
    public void onResume() {
        super.onResume();
        final String id = whiteboardId;
        executors.diskIO(() -> {
            if (whiteboardRepo.getByIdSync(id) != null) return;
            executors.mainThread(() -> {
                if (!isAdded()) return;
                // The board is gone or shut away, so the session on it has nothing left to be
                // about. No warning here — this exit was never the user's choice to make.
                if (collab.isInSession()) collab.endSession();
                navigateUp();
            });
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        commitText();
        saveTitle();
    }

    @Override
    public void onStart() {
        super.onStart();
        collab.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
        collab.onStop();
    }

    @Override
    public void onDestroyView() {
        // Before the view references go: the decision is made from what the canvas holds.
        discardIfNeverUsed();

        super.onDestroyView();
        collab.onDestroyView();
        // Null out view references to avoid holding onto a destroyed View
        whiteboardView = null;
        titleInput     = null;
        textEditor     = null;
        collabPeople   = null;
    }

    /**
     * Throws away a board that was opened and left without anything ever being put on it.
     *
     * <p>The row exists from the moment the canvas opens — it has to, since strokes carry a foreign
     * key onto it — so without this every glance at a new board left an empty rectangle on Home
     * forever. Backing out with the system's edge gesture was the common way to produce one.
     *
     * <p>Four things protect a board from this, and each is a way of saying it isn't junk:
     *
     * <ul>
     *   <li>anything drawn or typed on it ({@link WhiteboardView#hasContent()}, asked of the view
     *       rather than the database so a stroke still being written can't be missed);
     *   <li>a title, which is somebody having named it for later;
     *   <li>belonging to a note, either as its own {@code note_id} or as an embed — deleting one of
     *       those would leave the note rendering a board that is gone;
     *   <li>a configuration change, which is not the user leaving at all.
     * </ul>
     *
     * <p>And it only ever applies to a board created for this visit ({@link #ARG_CREATED_NOW}). An
     * empty board made in an earlier session is somebody's empty board — they may be keeping it to
     * draw on later, and a screen they merely opened and closed is no reason to take it away.
     */
    private void discardIfNeverUsed() {
        if (!createdThisSession) return;
        if (getActivity() == null || requireActivity().isChangingConfigurations()) return;
        if (whiteboardView == null || whiteboardView.hasContent()) return;
        if (titleInput != null && !titleInput.getText().toString().trim().isEmpty()) return;
        if (noteId != null) return;

        String id = whiteboardId;
        Context appContext = requireContext().getApplicationContext();
        AppExecutors.getInstance().diskIO(() -> {
            WhiteboardRepository repository = new WhiteboardRepository(appContext);
            // The last check, and the only one that needs the database: a board with no note_id of
            // its own can still have been imported into somebody's note.
            if (repository.embeddingNoteCountSync(id) > 0) return;
            repository.deleteWhiteboard(id, null);
        });
    }

    // ── View binding ──────────────────────────────────────────────────────────

    /** Finds every UI element declared in fragment_whiteboard.xml by its android:id. */
    private void bindViews(View root) {
        whiteboardView  = root.findViewById(R.id.whiteboardView);
        titleInput      = root.findViewById(R.id.titleInput);

        btnPen          = root.findViewById(R.id.btnPen);
        btnEraser       = root.findViewById(R.id.btnEraser);
        btnHighlighter  = root.findViewById(R.id.btnHighlighter);
        btnMove         = root.findViewById(R.id.btnMove);
        btnText         = root.findViewById(R.id.btnText);
        textEditor      = root.findViewById(R.id.textEditor);

        btnColorBlack   = root.findViewById(R.id.btnColorBlack);
        btnColorRed     = root.findViewById(R.id.btnColorRed);
        btnColorBlue    = root.findViewById(R.id.btnColorBlue);
        btnColorGreen   = root.findViewById(R.id.btnColorGreen);
        btnColorYellow  = root.findViewById(R.id.btnColorYellow);

        btnWidthThin    = root.findViewById(R.id.btnWidthThin);
        btnWidthMedium  = root.findViewById(R.id.btnWidthMedium);
        btnWidthThick   = root.findViewById(R.id.btnWidthThick);
        btnWidthExtraThick = root.findViewById(R.id.btnWidthExtraThick);

        root.findViewById(R.id.back_button).setOnClickListener(v -> collab.attemptExit());

        leftSidebar     = root.findViewById(R.id.leftSidebar);
        btnToggleTools  = root.findViewById(R.id.btnToggleTools);
        btnBackground   = root.findViewById(R.id.btnBackground);
        btnCentre       = root.findViewById(R.id.btnCentre);
        btnUndo         = root.findViewById(R.id.btnUndo);
        btnClear        = root.findViewById(R.id.btnClear);
        btnExport       = root.findViewById(R.id.btnExport);
        btnCollab       = root.findViewById(R.id.btnCollab);
        btnPip          = root.findViewById(R.id.btnPip);
        collabPeople    = root.findViewById(R.id.collabPeople);
        topToolbar      = root.findViewById(R.id.topToolbar);
    }

    /** Attaches click listeners to every toolbar button. */
    private void setupToolbar() {
        // Tool selection
        btnPen.setOnClickListener(v -> selectTool(WhiteboardView.TOOL_PEN, btnPen));
        // Text and Move draw nothing, so they leave the pen/eraser/highlighter choice underneath.
        btnText.setOnClickListener(v -> {
            whiteboardView.setInputMode(WhiteboardView.MODE_TEXT);
            highlightTool(btnText);
        });
        btnEraser.setOnClickListener(v -> selectTool(WhiteboardView.TOOL_ERASER, btnEraser));
        btnHighlighter.setOnClickListener(v ->
                selectTool(WhiteboardView.TOOL_HIGHLIGHTER, btnHighlighter));
        btnMove.setOnClickListener(v -> {
            whiteboardView.setInputMode(WhiteboardView.MODE_MOVE);
            highlightTool(btnMove);
        });

        // Color selection
        btnColorBlack.setOnClickListener(v  -> selectColor(Color.BLACK, btnColorBlack));
        btnColorRed.setOnClickListener(v    -> selectColor(Color.RED, btnColorRed));
        btnColorBlue.setOnClickListener(v   -> selectColor(Color.BLUE, btnColorBlue));
        btnColorGreen.setOnClickListener(v  -> selectColor(Color.GREEN, btnColorGreen));
        btnColorYellow.setOnClickListener(v -> selectColor(Color.YELLOW, btnColorYellow));

        // Stroke width selection
        btnWidthThin.setOnClickListener(v   -> selectWidth(3f, btnWidthThin));
        btnWidthMedium.setOnClickListener(v -> selectWidth(7f, btnWidthMedium));
        btnWidthThick.setOnClickListener(v  -> selectWidth(14f, btnWidthThick));
        btnWidthExtraThick.setOnClickListener(v -> selectWidth(26f, btnWidthExtraThick));

        // Actions
        btnToggleTools.setOnClickListener(v -> setToolsVisible(leftSidebar.getVisibility() != View.VISIBLE));
        btnBackground.setOnClickListener(this::showBackgroundMenu);
        // Confirmed out loud, because the usual outcome is that nothing moves: a board that is
        // already centred (or has nothing on it to centre) answers a tap by looking identical, and
        // a button that appears to do nothing is one nobody presses twice.
        btnCentre.setOnClickListener(v -> {
            whiteboardView.centreOnContent();
            showTransientMessage(R.string.whiteboard_centred);
        });
        btnUndo.setOnClickListener(v   -> undoLastStroke());
        btnClear.setOnClickListener(v  -> confirmClear());
        btnExport.setOnClickListener(anchor -> export.showExportMenu(anchor));
        btnCollab.setOnClickListener(v -> collab.onCollabButtonClicked());
        collabPeople.setOnClickListener(v -> showCollabRoster());
        btnPip.setOnClickListener(v -> enterPipIfPossible());
        btnPip.setVisibility(pipSupported() ? View.VISIBLE : View.GONE);

        // Set sensible defaults on screen open
        selectTool(WhiteboardView.TOOL_PEN, btnPen);
        selectColor(Color.BLACK, btnColorBlack);
        selectWidth(7f, btnWidthMedium);
        setUpTitle();
    }

    /**
     * Loads the board's name into the toolbar field and saves edits back.
     *
     * <p>The hint is the same "Untitled Whiteboard - <date>" fallback the lists resolve, so an
     * unnamed board reads identically here and on Home — and stays as easy to name as it was on
     * the day it was drawn. Saving happens on pause rather than per keystroke: a rename is one
     * short burst of typing, and a write per character would fight the stroke inserts for the
     * same disk thread.
     */
    private void setUpTitle() {
        if (titleInput == null) return;
        final String id = whiteboardId;
        // The context is resolved here, on the main thread: requireContext() from a background
        // thread throws the moment the fragment detaches, which is exactly when a slow read lands.
        final Context context = requireContext().getApplicationContext();
        executors.diskIO(() -> {
            Whiteboard board = whiteboardRepo.getByIdSync(id);
            if (board == null) return;
            String name = board.title;
            String hint = NoteDisplayUtils.resolveWhiteboardTitle(context, board);
            int background = board.background;
            executors.mainThread(() -> {
                if (titleInput == null) return;
                loadedTitle = name;
                titleInput.setHint(hint);
                if (name != null && !name.trim().isEmpty()) titleInput.setText(name);
                applyBackground(background);
            });
        });
    }

    /**
     * Writes the typed name back, or clears it so the board falls back to its dated hint.
     *
     * <p>Only when it actually changed. This runs on every pause, and rename bumps updated_at, so
     * writing unconditionally meant opening a board and backing out of it reported "Updated now"
     * on Home and jumped it to the top of the list.
     */
    private void saveTitle() {
        if (titleInput == null) return;
        String typed = titleInput.getText().toString().trim();
        final String value = typed.isEmpty() ? null : typed;
        if (java.util.Objects.equals(value, loadedTitle)) return;

        loadedTitle = value;
        final String id = whiteboardId;
        executors.diskIO(() -> whiteboardRepo.renameSync(id, value));
    }

    /**
     * Offers the three papers. A menu rather than a cycling button: three states you can't see the
     * order of are worse to cycle through than to pick from.
     */
    private void showBackgroundMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, WhiteboardView.BACKGROUND_WHITE, 0, R.string.background_white);
        menu.getMenu().add(0, WhiteboardView.BACKGROUND_PAPER, 1, R.string.background_paper);
        menu.getMenu().add(0, WhiteboardView.BACKGROUND_DOTS, 2, R.string.background_dots);
        menu.setOnMenuItemClickListener(item -> {
            final int style = item.getItemId();
            applyBackground(style);
            WhiteboardPreferences.setDefaultBackground(requireContext(), style);
            final String id = whiteboardId;
            executors.diskIO(() -> whiteboardRepo.setBackgroundSync(id, style));
            return true;
        });
        menu.show();
    }

    private void applyBackground(int style) {
        if (whiteboardView != null) whiteboardView.setBackgroundStyle(style);
    }

    /**
     * Opens the editor where the canvas was tapped.
     *
     * <p>The field is placed once and does not follow the canvas, because panning is suspended
     * while it is open — that is what keeps this a text *placement* feature rather than an
     * editing layer with a floating view to keep in sync.
     */
    private void beginText(float canvasX, float canvasY) {
        commitText();  // a second tap commits the first item rather than losing it
        pendingTextX = canvasX;
        pendingTextY = canvasY;

        textEditor.setText("");
        textEditor.setTextColor(currentColor);
        textEditor.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx());
        // Canvas coordinates minus the scroll offset is where that point currently sits on screen.
        textEditor.setX(whiteboardView.getLeft() + canvasX - whiteboardView.getScrollX());
        textEditor.setY(whiteboardView.getTop() + canvasY - whiteboardView.getScrollY());
        textEditor.setVisibility(View.VISIBLE);
        textEditor.requestFocus();
        InputMethodManager ime = (InputMethodManager)
                requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.showSoftInput(textEditor, InputMethodManager.SHOW_IMPLICIT);
    }

    /**
     * Turns whatever has been typed into an item on the board, or throws it away if it is blank.
     * Committed text is immutable, exactly like a stroke: it can be undone, not edited.
     */
    private void commitText() {
        if (textEditor == null || textEditor.getVisibility() != View.VISIBLE) return;

        String typed = textEditor.getText().toString().trim();
        textEditor.setVisibility(View.GONE);
        InputMethodManager ime = (InputMethodManager)
                requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(textEditor.getWindowToken(), 0);
        if (typed.isEmpty()) return;

        WhiteboardText item = new WhiteboardText();
        item.id           = UUID.randomUUID().toString();
        item.whiteboardId = whiteboardId;
        item.authorId     = "local-user";
        item.x            = pendingTextX;
        item.y            = pendingTextY;
        item.text         = typed;
        item.color        = currentColor;
        item.size         = textSizePx();
        item.createdAt    = System.currentTimeMillis();

        whiteboardView.addText(item);
        undoStack.push(new Undoable(item.id, true, item.createdAt));
        noteLastEdit(item.x, item.y);
        textRepo.insert(item);
        touchWhiteboard();
        collab.sendText(item);
    }

    /**
     * Text size follows the stroke width picker, so the rail keeps one meaning of "how big" —
     * a thin pen writes small text, the heaviest writes a heading.
     */
    private float textSizePx() {
        return currentWidth * TEXT_SIZE_PER_STROKE_WIDTH;
    }

    /**
     * Shows or hides the tool rail, handing its width to the canvas either way.
     *
     * <p>The button shows what the next tap does rather than the current state — the eye is struck
     * through while there is something to strike out.
     */
    private void setToolsVisible(boolean visible) {
        leftSidebar.setVisibility(visible ? View.VISIBLE : View.GONE);
        btnToggleTools.setImageResource(visible ? R.drawable.ic_invisible : R.drawable.ic_visible);
        btnToggleTools.setContentDescription(
                getString(visible ? R.string.action_hide_tools : R.string.action_show_tools));
    }

    private void selectTool(int tool, ImageButton active) {
        whiteboardView.setTool(tool);
        whiteboardView.setInputMode(WhiteboardView.MODE_DRAW);
        highlightTool(active);
    }

    private void highlightTool(ImageButton active) {
        btnPen.setSelected(false);
        btnEraser.setSelected(false);
        btnHighlighter.setSelected(false);
        btnMove.setSelected(false);
        btnText.setSelected(false);
        commitText();  // leaving the text tool commits whatever was being typed
        active.setSelected(true);
    }

    /** Every option that can be picked shows it the same way — the swatch or bar lights up. */
    private void selectWidth(float width, ImageButton active) {
        currentWidth = width;
        whiteboardView.setStrokeWidth(width);
        btnWidthThin.setSelected(false);
        btnWidthMedium.setSelected(false);
        btnWidthThick.setSelected(false);
        btnWidthExtraThick.setSelected(false);
        active.setSelected(true);
    }

    private void selectColor(int color, ImageButton swatch) {
        currentColor = color;
        whiteboardView.setColor(color);
        btnColorBlack.setSelected(false);
        btnColorRed.setSelected(false);
        btnColorBlue.setSelected(false);
        btnColorGreen.setSelected(false);
        btnColorYellow.setSelected(false);
        swatch.setSelected(true);
    }

    // ── WhiteboardView.StrokeListener ─────────────────────────────────────────

    /**
     * Called automatically by WhiteboardView every time the user lifts
     * their finger after drawing a stroke. This is where persistence happens.
     */
    @Override
    public void onStrokeComplete(Stroke stroke) {
        stroke.whiteboardId = whiteboardId;
        undoStack.push(new Undoable(stroke.id, false, stroke.createdAt));
        noteLastEdit(stroke);

        // Save to SQLite on a background thread (never touch DB on the UI thread)
        strokeRepo.insertStroke(stroke);
        touchWhiteboard();
        collab.sendStroke(stroke);
    }

    // ── Undo / Clear ──────────────────────────────────────────────────────────

    /**
     * The board's one line of feedback, replacing whatever it last said.
     *
     * <p>Cancelled before being shown again, because Android <em>queues</em> toasts: tapping undo
     * on an empty stack five times used to mean five "Nothing to undo" in a row, each waiting out
     * the last. Holding the instance and cancelling it turns a queue into one message that keeps
     * restarting, which is what repeating the same tap should look like — and, since every message
     * on this screen goes through here, the same is true of two different taps in a row.
     */
    private void showTransientMessage(int textRes) {
        if (transientToast != null) transientToast.cancel();
        transientToast = Toast.makeText(requireContext(), textRes, Toast.LENGTH_SHORT);
        transientToast.show();
    }

    private void undoLastStroke() {
        if (undoStack.isEmpty()) {
            showTransientMessage(R.string.whiteboard_nothing_to_undo);
            return;
        }
        Undoable last = undoStack.pop(); // removes and returns the top of the stack

        if (last.text) {
            whiteboardView.removeText(last.id);
            textRepo.delete(last.id);
        } else {
            whiteboardView.removeStroke(last.id);
            strokeRepo.deleteStroke(last.id);
        }
        touchWhiteboard();
        collab.sendRetract(last.id, last.text);
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.whiteboard_clear_title)
                .setMessage(R.string.whiteboard_clear_message)
                .setPositiveButton(R.string.whiteboard_clear_confirm, (d, w) -> clearWhiteboard())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Wiping the board because <em>this</em> device asked to: the same wipe a peer's CLEAR
     *  causes ({@link #clearAll}), plus telling everyone else to do it too. */
    private void clearWhiteboard() {
        clearAll();
        touchWhiteboard();
        collab.sendClear();
    }

    // ── WhiteboardExportController.Host ──────────────────────────────────────

    @Override
    public String boardId() {
        return whiteboardId;
    }

    @Override
    public String typedTitle() {
        return titleInput != null ? titleInput.getText().toString().trim() : "";
    }

    @Override
    public Bitmap renderBoard() {
        return whiteboardView == null ? null : whiteboardView.exportToBitmap();
    }

    @Override
    public void showMessage(int textRes) {
        showTransientMessage(textRes);
    }

    // ── Picture-in-Picture ───────────────────────────────────────────────────────

    private boolean pipSupported() {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && getActivity() != null
                && requireActivity().getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    /** Shrinks the board into Android's floating PIP window, sized to whatever this board's canvas
     *  actually looks like — see {@code MainActivity#enterWhiteboardPip}. Also what {@code
     *  MainActivity#onUserLeaveHint} calls when the user leaves the app while this screen is up. */
    public void enterPipIfPossible() {
        if (!pipSupported() || getActivity() == null || whiteboardView == null) return;
        int w = whiteboardView.getWidth();
        int h = whiteboardView.getHeight();
        if (w <= 0 || h <= 0) return; // not laid out yet
        // The interface, not the concrete Activity: the trip out to PIP now goes through the same
        // contract the trip back in does — see PipAware.
        if (requireActivity() instanceof PipAware.PipHost) {
            ((PipAware.PipHost) requireActivity()).enterWhiteboardPip(w, h);
        }
    }

    /** Where the canvas was scrolled to before PIP took over, so leaving PIP puts the window back
     *  where the user left it rather than wherever centring for PIP happened to land. */
    private int scrollXBeforePip;
    private int scrollYBeforePip;

    /** Where the most recent stroke or text landed, local or from a collaborator — see
     *  {@link #noteLastEdit}. Null until something has actually been drawn this session, since a
     *  freshly opened board has no "last edit" to favour over the middle of the page. */
    private Float lastEditX;
    private Float lastEditY;

    /** Records where an edit landed, so PIP can favour "wherever the ink is happening" over the
     *  centre of everything ever drawn — the two agree on a board with one thing on it, and
     *  diverge on a big shared one where a collaborator is working in a corner nowhere near the
     *  board's overall centre of mass. Called for both local edits and ones received over a live
     *  collab session, so a peer's strokes can re-aim this device's PIP window too. */
    private void noteLastEdit(float canvasX, float canvasY) {
        lastEditX = canvasX;
        lastEditY = canvasY;
    }

    /** Last point of the stroke — where the pen lifted, which is more "where the drawing is" than
     *  its first touch-down for anything but a short mark. */
    private void noteLastEdit(Stroke stroke) {
        if (stroke.points == null || stroke.points.isEmpty()) return;
        android.graphics.PointF last = stroke.points.get(stroke.points.size() - 1);
        noteLastEdit(last.x, last.y);
    }

    /** Nothing on the tool rail or top bar is reachable at PIP size — touch input doesn't even
     *  reach the floating window — so it comes off entirely rather than sitting there unusable,
     *  leaving just the drawing itself to look at. Restored exactly as it was on the way back.
     *
     * <p>Hiding them also widens the canvas view to fill the space they took, which on its own
     * would leave whatever corner was on screen before still on screen — most often the top-left,
     * since that's where a board opens. Centring on the most recent edit — whoever made it — is
     * what actually makes a floating window worth glancing at; falling back to all the ink's
     * centre of mass only for a board nothing has been drawn on yet this session. */
    @Override
    public void onPipModeChanged(boolean isInPictureInPictureMode) {
        if (leftSidebar == null || topToolbar == null || whiteboardView == null) return;
        if (isInPictureInPictureMode) {
            sidebarVisibleBeforePip = leftSidebar.getVisibility() == View.VISIBLE;
            scrollXBeforePip = whiteboardView.getScrollX();
            scrollYBeforePip = whiteboardView.getScrollY();
            leftSidebar.setVisibility(View.GONE);
            topToolbar.setVisibility(View.GONE);
            commitText(); // the on-screen keyboard has nowhere to go in a floating window
            // Posted: the rail/toolbar going away only resizes whiteboardView once this layout pass
            // runs, and centring before that measures against the old, narrower bounds.
            final Float editX = lastEditX;
            final Float editY = lastEditY;
            whiteboardView.post(() -> {
                if (whiteboardView == null) return;
                if (editX != null && editY != null) whiteboardView.centreOn(editX, editY);
                else whiteboardView.centreOnContent();
            });
        } else {
            leftSidebar.setVisibility(sidebarVisibleBeforePip ? View.VISIBLE : View.GONE);
            topToolbar.setVisibility(View.VISIBLE);
            whiteboardView.post(() -> {
                if (whiteboardView != null) whiteboardView.scrollTo(scrollXBeforePip, scrollYBeforePip);
            });
        }
    }

    // ── WhiteboardCollabController.Host ──────────────────────────────────────
    //
    // What a live session is allowed to do to this board, and what it needs from the screen
    // around it. The controller calls these; this fragment never sees a CollabMessage.

    /**
     * A stroke from a peer.
     *
     * <p>The re-tagging is not cosmetic. The peer's {@code whiteboardId} is *their* board's row —
     * each side opened (or created) its own {@code whiteboards} row locally — so a stroke has to
     * be re-tagged onto this device's id before it can satisfy the {@code strokes → whiteboards}
     * foreign key. Left as the peer's own id, insertStroke throws SQLITE_CONSTRAINT_FOREIGNKEY and
     * takes the process with it, which is exactly what a real two-device run surfaced.
     */
    @Override
    public void applyStroke(Stroke stroke) {
        if (whiteboardView == null) return;
        stroke.whiteboardId = whiteboardId;
        whiteboardView.addStroke(stroke);
        strokeRepo.insertStroke(stroke);
        touchWhiteboard();
        noteLastEdit(stroke);
    }

    /** Same contract, and the same re-tagging, as {@link #applyStroke}. */
    @Override
    public void applyText(WhiteboardText text) {
        if (whiteboardView == null) return;
        text.whiteboardId = whiteboardId;
        whiteboardView.addText(text);
        textRepo.insert(text);
        touchWhiteboard();
        noteLastEdit(text.x, text.y);
    }

    @Override
    public void retract(String id, boolean isText) {
        if (whiteboardView == null) return;
        if (isText) {
            whiteboardView.removeText(id);
            textRepo.delete(id);
        } else {
            whiteboardView.removeStroke(id);
            strokeRepo.deleteStroke(id);
        }
        touchWhiteboard();
    }

    /** Also what the Clear button does locally before it broadcasts — see
     *  {@link #clearWhiteboard}. No re-broadcast from here: a CLEAR that arrives was already sent
     *  to every peer by the host directly. */
    @Override
    public void clearAll() {
        if (whiteboardView == null) return;
        whiteboardView.clearAll();
        undoStack.clear(); // nothing left to undo once everything is wiped
        strokeRepo.deleteAllForWhiteboard(whiteboardId);
        textRepo.deleteAllForWhiteboard(whiteboardId);
    }

    /**
     * The host's whole board, replacing whatever this device had. Arrives already ordered — see
     * {@link WhiteboardCanvas#replaceAll}.
     *
     * <p>The database half is one block on the disk thread using the blocking calls, because the
     * delete has to be finished before the first insert lands and four independent async calls
     * would not promise that.
     */
    @Override
    public void replaceAll(List<Stroke> strokes, List<WhiteboardText> texts) {
        if (whiteboardView == null) return;
        // Same re-tagging as a live stroke or text, and for the same reason: every item in the
        // host's snapshot still carries the host's own whiteboard_id.
        for (Stroke s : strokes) s.whiteboardId = whiteboardId;
        for (WhiteboardText t : texts) t.whiteboardId = whiteboardId;

        whiteboardView.clearAll();
        undoStack.clear();
        for (Stroke s : strokes) whiteboardView.addStroke(s);
        for (WhiteboardText t : texts) whiteboardView.addText(t);
        whiteboardView.centreOnContent();

        executors.diskIO(() -> {
            strokeRepo.deleteAllForWhiteboardSync(whiteboardId);
            textRepo.deleteAllForWhiteboardSync(whiteboardId);
            for (Stroke s : strokes) strokeRepo.insertStrokeSync(s);
            for (WhiteboardText t : texts) textRepo.insertSync(t);
        });
    }

    /** Asked of the view rather than the database, so a stroke still being written can't be
     *  missed — the same reason {@link #discardIfNeverUsed} asks it that way. */
    @Override
    public boolean hasBoardContent() {
        return whiteboardView != null && whiteboardView.hasContent();
    }

    /**
     * Duplicates this board — strokes, text, background and title — into a new one on Home, then
     * runs {@code then}.
     *
     * <p>A copy rather than a move: the board being joined has to stay where it is, since it is
     * the one the session fills, and which of the two ends up holding the old drawing should not
     * be something the user has to reason about.
     */
    @Override
    public void saveCopyOfBoard(Runnable then) {
        final String sourceId = whiteboardId;
        final String typed = titleInput != null ? titleInput.getText().toString().trim() : "";
        final String copyTitle = typed.isEmpty()
                ? null : getString(R.string.collab_join_copy_name, typed);
        final Context appContext = requireContext().getApplicationContext();
        executors.diskIO(() -> {
            Whiteboard source = whiteboardRepo.getByIdSync(sourceId);
            List<Stroke> strokes = strokeRepo.getByWhiteboardSync(sourceId);
            List<WhiteboardText> texts = textRepo.getByWhiteboardSync(sourceId);
            int background = source != null
                    ? source.background : WhiteboardPreferences.defaultBackground(appContext);
            // The callback lands on the main thread, so the row copying goes back to the disk
            // thread — where it queues behind this very block rather than racing it.
            whiteboardRepo.createWhiteboard(copyTitle, null, background, copyId -> executors.diskIO(() -> {
                // New ids: these rows are a second board now, not the same one twice.
                for (Stroke stroke : strokes) {
                    stroke.id = UUID.randomUUID().toString();
                    stroke.whiteboardId = copyId;
                    strokeRepo.insertStrokeSync(stroke);
                }
                for (WhiteboardText text : texts) {
                    text.id = UUID.randomUUID().toString();
                    text.whiteboardId = copyId;
                    textRepo.insertSync(text);
                }
                executors.mainThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), R.string.collab_join_copy_saved,
                            Toast.LENGTH_SHORT).show();
                    then.run();
                });
            }));
        });
    }

    /** The roster as a count in the top bar; the names are kept for {@link #showCollabRoster}. */
    @Override
    public void showRoster(List<String> names) {
        collabRoster = names;
        if (collabPeople == null) return;
        if (names.isEmpty()) {
            collabPeople.setVisibility(View.GONE);
            return;
        }
        int count = names.size();
        collabPeople.setVisibility(View.VISIBLE);
        collabPeople.setText(String.valueOf(count));
        collabPeople.setContentDescription(
                getResources().getQuantityString(R.plurals.collab_people_count, count, count));
    }

    /** Clear is host-only in a live session — a joiner sees the button disabled entirely, rather
     *  than tappable-but-rejected, so there's nothing to discover the hard way. */
    @Override
    public void applyCollabRole(boolean inSession, boolean isHost) {
        if (btnClear != null) btnClear.setEnabled(!inSession || isHost);
        if (btnCollab != null) {
            btnCollab.setSelected(inSession);
            btnCollab.setContentDescription(getString(inSession
                    ? R.string.collab_end_session : R.string.action_collaborate));
        }
    }

    @Override
    public void navigateUp() {
        if (!isAdded()) return;
        androidx.navigation.fragment.NavHostFragment.findNavController(this).navigateUp();
    }

    /** The names behind the count. A list, not a row of chips: it is read once, when someone
     *  wonders who else is here, and it costs the board nothing the rest of the time. */
    private void showCollabRoster() {
        List<String> names = collabRoster;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getResources().getQuantityString(
                        R.plurals.collab_people_count, names.size(), names.size()))
                .setItems(names.toArray(new String[0]), null)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
