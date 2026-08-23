package mse.quill.ui.whiteboard;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.util.TypedValue;
import android.view.inputmethod.InputMethodManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.PopupMenu;
import android.widget.Toast;
import android.provider.MediaStore;
import android.content.ContentValues;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.fragment.app.Fragment;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import mse.quill.R;
import mse.quill.collab.CollabMessage;
import mse.quill.collab.CollabSession;
import mse.quill.collab.CollabPermissions;
import mse.quill.collab.CollabSessionHolder;
import mse.quill.collab.QrCodes;
import mse.quill.collab.SessionCode;
import mse.quill.collab.SessionScanner;
import mse.quill.data.AppDatabase;
import mse.quill.data.StrokeRepository;
import mse.quill.data.AppExecutors;
import mse.quill.data.WhiteboardRepository;
import mse.quill.data.WhiteboardTextRepository;
import mse.quill.data.model.Stroke;
import mse.quill.data.model.Whiteboard;
import mse.quill.data.model.WhiteboardText;
import mse.quill.ui.profile.ProfilePreferences;
import mse.quill.util.NoteDisplayUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
        implements WhiteboardView.StrokeListener, mse.quill.util.PipAware {

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
    /** Mirrors {@code CollabSessionHolder.session()} — re-synced in {@link #onStart()} so a
     *  fragment recreated while a session is still alive (rotation, back-and-forth nav) picks it
     *  back up instead of losing track of it. */
    private CollabSession collabSession;
    private boolean isCollabHost;
    private CollabDialogs.StatusDialog collabStatusDialog;
    /** The whole roster, as one count in the top bar. Its state is derived from
     *  {@link CollabSession#currentPeers()} on every roster event rather than mirrored here —
     *  there is one list of who is in a session, and it belongs to the session. */
    private MaterialButton collabPeople;
    /** A snapshot being reassembled from its chunks — see {@link #applySnapshot}. Empty except in
     *  the moment between a joiner connecting and the host's board arriving in full. */
    private final Set<Integer> pendingSnapshotChunks = new HashSet<>();
    private final List<Stroke> pendingSnapshotStrokes = new ArrayList<>();
    private final List<WhiteboardText> pendingSnapshotTexts = new ArrayList<>();
    private int pendingSnapshotCount;
    /** What to do once the Nearby permission prompt below resolves. */
    private Runnable pendingCollabAction;
    private final ActivityResultLauncher<String[]> collabPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), results -> {
                boolean allGranted = !results.containsValue(false);
                Runnable action = pendingCollabAction;
                pendingCollabAction = null;
                if (allGranted && action != null) {
                    action.run();
                } else if (!allGranted) {
                    Toast.makeText(requireContext(), R.string.collab_permission_denied, Toast.LENGTH_LONG).show();
                }
            });

    // ── Data ──────────────────────────────────────────────────────────────────
    private StrokeRepository     strokeDao;
    private WhiteboardTextRepository textDao;
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

        // 2. Get access to the database (singleton — safe to call anywhere)
        AppDatabase db = AppDatabase.getInstance(requireContext());
        strokeDao      = new StrokeRepository(db);
        textDao        = new WhiteboardTextRepository(db);
        whiteboardRepo = new WhiteboardRepository(db);

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
            // Synchronous on purpose: `strokes` has a foreign key onto this row, and the stroke
            // inserts below run on their own unordered threads, so the parent row has to exist
            // before the canvas is even shown.
            whiteboardRepo.insertSync(wb);
        }
    }

    /**
     * Bumps the board's updated_at so Home's Whiteboards section sorts by real recency. Called on
     * every canvas change rather than on exit, since the fragment can go away without onStop work
     * completing.
     */
    private void touchWhiteboard() {
        final String id = whiteboardId;
        final long now = System.currentTimeMillis();
        new Thread(() -> whiteboardRepo.touchSync(id, now)).start();
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
                        attemptExit();
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
            // The permission ladder still applies — the board was opened for this join, so a
            // refusal leaves an empty board, which discardIfNeverUsed then takes away again.
            requestCollabPermissionsThen(() -> joinWithToken(joinToken));
        }

        // Load any strokes already saved for this whiteboard (e.g. reopening a note)
        // Runs on a background thread because SQLite reads should not block the UI thread.
        new Thread(() -> {
            List<Stroke> existing = strokeDao.getByWhiteboard(whiteboardId);
            List<WhiteboardText> existingText = textDao.getByWhiteboard(whiteboardId);
            requireActivity().runOnUiThread(() -> {
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
        }).start();
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
        new Thread(() -> {
            if (whiteboardRepo.getByIdSync(id) != null) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                // The board is gone or shut away, so the session on it has nothing left to be
                // about. No warning here — this exit was never the user's choice to make.
                if (collabSession != null) endCollabSession();
                androidx.navigation.fragment.NavHostFragment.findNavController(this).navigateUp();
            });
        }).start();
    }

    @Override
    public void onPause() {
        super.onPause();
        commitText();
        saveTitle();
    }

    /** Picks up whatever collab session is already alive (survives this fragment being torn down
     *  and recreated) rather than assuming there is none — see {@link CollabSessionHolder}. */
    @Override
    public void onStart() {
        super.onStart();
        // A session belongs to the board it was started on, and only to that board. Without this
        // check, opening any whiteboard while one was alive adopted it — a brand-new board would
        // open already in someone else's session, drawing on top of their work. A session this
        // screen starts itself attaches at that point instead; see startHosting.
        if (!CollabSessionHolder.isFor(whiteboardId)) return;

        // Role and roster first, listener second: attaching replays whatever happened while this
        // screen was away, and those callbacks read isCollabHost — a host that hadn't re-synced
        // yet would handle its own replayed events as if it were a joiner.
        collabSession = CollabSessionHolder.session();
        isCollabHost = CollabSessionHolder.isHost();
        updateCollabRoster();
        applyCollabRoleToUi();
        CollabSessionHolder.attach(collabListener);
        // Back on the board — say so, since the session may have carried on without this screen.
        CollabSessionHolder.setViewing(true);
    }

    /** Only stops routing callbacks to this screen — the session itself lives on in
     *  {@link CollabSessionHolder} until explicitly ended/left. */
    @Override
    public void onStop() {
        super.onStop();
        // Still in the session, no longer at the board: the others should stop counting this
        // device among the people they are drawing with until it comes back. Only for the board
        // the session is actually on — another board's screen has nothing to say about it.
        if (CollabSessionHolder.isFor(whiteboardId)) CollabSessionHolder.setViewing(false);
        CollabSessionHolder.detach(collabListener);
    }

    @Override
    public void onDestroyView() {
        // Before the view references go: the decision is made from what the canvas holds.
        discardIfNeverUsed();

        super.onDestroyView();
        if (collabStatusDialog != null) {
            collabStatusDialog.dismiss();
            collabStatusDialog = null;
        }
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

        root.findViewById(R.id.back_button).setOnClickListener(v -> attemptExit());

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
        btnExport.setOnClickListener(this::showExportMenu);
        btnCollab.setOnClickListener(v -> showCollabEntry());
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
        new Thread(() -> {
            Whiteboard board = whiteboardRepo.getByIdSync(id);
            if (board == null) return;
            String name = board.title;
            String hint = NoteDisplayUtils.resolveWhiteboardTitle(requireContext(), board);
            int background = board.background;
            requireActivity().runOnUiThread(() -> {
                if (titleInput == null) return;
                loadedTitle = name;
                titleInput.setHint(hint);
                if (name != null && !name.trim().isEmpty()) titleInput.setText(name);
                applyBackground(background);
            });
        }).start();
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
        new Thread(() -> whiteboardRepo.renameSync(id, value)).start();
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
            new Thread(() -> whiteboardRepo.setBackgroundSync(id, style)).start();
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
        new Thread(() -> textDao.insert(item)).start();
        touchWhiteboard();
        if (collabSession != null && collabSession.isConnected()) {
            collabSession.send(CollabMessage.text(item));
        }
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

        // Save to SQLite on a background thread (never touch DB on the UI thread)
        new Thread(() -> strokeDao.insertStroke(stroke)).start();
        touchWhiteboard();
        if (collabSession != null && collabSession.isConnected()) {
            collabSession.send(CollabMessage.stroke(stroke));
        }
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
            new Thread(() -> textDao.delete(last.id)).start();
        } else {
            whiteboardView.removeStroke(last.id);
            new Thread(() -> strokeDao.deleteStroke(last.id)).start();
        }
        touchWhiteboard();
        // Undo only ever pops something *this device* added — received strokes/text are never
        // pushed onto undoStack — so this is always "retract my own last item", per requirements.md.
        if (collabSession != null && collabSession.isConnected()) {
            collabSession.send(CollabMessage.retract(last.id, last.text));
        }
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear Whiteboard")
                .setMessage("This will erase everything on this whiteboard. Continue?")
                .setPositiveButton("Clear", (d, w) -> clearWhiteboard())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearWhiteboard() {
        whiteboardView.clearAll();
        undoStack.clear(); // nothing left to undo once everything is wiped
        new Thread(() -> {
            strokeDao.deleteAllForWhiteboard(whiteboardId);
            textDao.deleteAllForWhiteboard(whiteboardId);
        }).start();
        touchWhiteboard();
        // Clear is destructive to everyone in a live session, so only the host may trigger it —
        // btnClear is disabled for a joiner (see applyCollabRoleToUi) — and the host tells the
        // peer to do the same rather than each side clearing independently.
        if (collabSession != null && collabSession.isConnected()) {
            collabSession.send(CollabMessage.clear());
        }
    }

    /** Applies a CLEAR received from the host — no re-broadcast, since the host already told
     *  every peer directly. */
    private void applyRemoteClear() {
        whiteboardView.clearAll();
        undoStack.clear();
        new Thread(() -> {
            strokeDao.deleteAllForWhiteboard(whiteboardId);
            textDao.deleteAllForWhiteboard(whiteboardId);
        }).start();
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /** Export as a flat image (lossy — a picture of the board) or share the board itself (lossless
     *  — the strokes and text another Quill can redraw and keep editing), mirroring the choice a
     *  note's Export menu already offers between PDF/Markdown and a {@code .quill} bundle. */
    private void showExportMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.whiteboard_export_image);
        menu.getMenu().add(0, 2, 1, R.string.whiteboard_share);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                exportWhiteboard();
            } else {
                shareWhiteboard();
            }
            return true;
        });
        menu.show();
    }

    /**
     * Packs the board into a {@code .quillboard} bundle and hands it to the system share sheet —
     * the same {@code ACTION_SEND} + FileProvider path a note's "Share to another Quill" uses, since
     * Quick Share, Bluetooth and mail are share <em>targets</em> here too, not APIs to integrate
     * with.
     */
    private void shareWhiteboard() {
        String id = whiteboardId;
        String name = titleInput != null ? titleInput.getText().toString().trim() : "";
        new Thread(() -> {
            Whiteboard board = whiteboardRepo.getByIdSync(id);
            if (board == null) return;
            List<Stroke> strokes = strokeDao.getByWhiteboard(id);
            List<WhiteboardText> texts = textDao.getByWhiteboard(id);
            String title = name.isEmpty() ? board.title : name;

            mse.quill.util.NoteExportStore.Saved saved = mse.quill.util.NoteExportStore.save(
                    requireContext().getApplicationContext(),
                    title == null ? "" : title,
                    mse.quill.share.WhiteboardBundle.EXTENSION,
                    mse.quill.share.WhiteboardBundle.MIME_TYPE,
                    out -> mse.quill.share.WhiteboardBundleWriter.write(
                            title, board.background, board.createdAt, board.updatedAt,
                            strokes, texts, out));

            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (saved == null) {
                    Toast.makeText(requireContext(), R.string.share_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType(mse.quill.share.WhiteboardBundle.MIME_TYPE)
                        .putExtra(android.content.Intent.EXTRA_STREAM, saved.uri)
                        .putExtra(android.content.Intent.EXTRA_TITLE, saved.displayName)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(android.content.Intent.createChooser(
                            send, getString(R.string.whiteboard_share_chooser)));
                } catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(requireContext(), R.string.share_no_target, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    /** Renders the current canvas to a PNG file in the device's Pictures folder. */
    private void exportWhiteboard() {
        if (whiteboardView == null) return;

        Bitmap bitmap   = whiteboardView.exportToBitmap();
        String filename = "whiteboard_" + System.currentTimeMillis() + ".png";
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename);
        values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
        // Save into Pictures/Quill so exports are grouped in their own album
        values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/Quill");
        android.content.ContentResolver resolver = requireContext().getContentResolver();
        android.net.Uri collection =
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        android.net.Uri itemUri = resolver.insert(collection, values);

        if (itemUri == null) {
            Log.e(TAG, "MediaStore insert returned null Uri");
            Toast.makeText(requireContext(), "Export failed", Toast.LENGTH_SHORT).show();
            return;
        }

        try (java.io.OutputStream out = resolver.openOutputStream(itemUri)) {
            if (out == null) throw new IOException("Could not open output stream");
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            Toast.makeText(requireContext(),
                    "Saved to Pictures/Quill/" + filename, Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Log.e(TAG, "Export failed", e);
            // Clean up the empty MediaStore entry if writing the bytes failed
            resolver.delete(itemUri, null, null);
            Toast.makeText(requireContext(), "Export failed", Toast.LENGTH_SHORT).show();
        }
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
        ((mse.quill.MainActivity) requireActivity()).enterWhiteboardPip(w, h);
    }

    /** Nothing on the tool rail or top bar is reachable at PIP size — touch input doesn't even
     *  reach the floating window — so it comes off entirely rather than sitting there unusable,
     *  leaving just the drawing itself to look at. Restored exactly as it was on the way back. */
    @Override
    public void onPipModeChanged(boolean isInPictureInPictureMode) {
        if (leftSidebar == null || topToolbar == null) return;
        if (isInPictureInPictureMode) {
            sidebarVisibleBeforePip = leftSidebar.getVisibility() == View.VISIBLE;
            leftSidebar.setVisibility(View.GONE);
            topToolbar.setVisibility(View.GONE);
            commitText(); // the on-screen keyboard has nowhere to go in a floating window
        } else {
            leftSidebar.setVisibility(sidebarVisibleBeforePip ? View.VISIBLE : View.GONE);
            topToolbar.setVisibility(View.VISIBLE);
        }
    }

    // ── Live collaboration (Epic C) ──────────────────────────────────────────────

    /** "Host a session" / "Join a session" — the entry point for the whole feature. */
    private void showCollabEntry() {
        if (collabSession != null) {
            // Already in a session: the button becomes "end session"/"leave" instead of opening
            // the choice again. Only a host ending it is destructive to everyone else — a joiner
            // leaving just removes themself, so the two get separate copy.
            if (isCollabHost) {
                // "Show code" rather than end-or-nothing: the session keeps accepting joiners for
                // as long as it is up, so the host needs a way back to the QR after putting it
                // away — otherwise a third device has no way in.
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.collab_end_session)
                        .setMessage(R.string.collab_leaving_locks_others_out)
                        .setPositiveButton(R.string.collab_end_session, (d, w) -> endCollabSession())
                        .setNeutralButton(R.string.collab_show_code, (d, w) -> showHostInvite())
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            } else {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.collab_leave_session)
                        .setPositiveButton(R.string.collab_leave_session, (d, w) -> endCollabSession())
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            }
            return;
        }
        CollabDialogs.showEntryDialog(requireContext(), new CollabDialogs.EntryListener() {
            @Override public void onHostChosen() { requestCollabPermissionsThen(WhiteboardFragment.this::startHosting); }
            @Override public void onJoinChosen() { requestCollabPermissionsThen(WhiteboardFragment.this::startJoinByScan); }
        });
    }

    /** Nearby needs the Bluetooth/location/Wi-Fi ladder documented in AndroidManifest.xml —
     *  version-gated, so a device only sees the prompts for permissions it actually has. */
    private void requestCollabPermissionsThen(Runnable action) {
        String[] missing = CollabPermissions.missing(requireContext());
        if (missing.length == 0) {
            action.run();
            return;
        }
        pendingCollabAction = action;
        collabPermissionLauncher.launch(missing);
    }

    /** The name shown to every other participant. Never the device model — see
     *  {@link ProfilePreferences#collabDisplayName}. */
    private String myCollabDisplayName() {
        return ProfilePreferences.collabDisplayName(requireContext());
    }

    private void startHosting() {
        isCollabHost = true;
        collabSession = CollabSessionHolder.host(requireContext(), myCollabDisplayName(), whiteboardId);
        // Attached here as well as in onStart: a session started from this screen begins after
        // onStart has already been and gone, and an unattached screen hears nothing at all.
        CollabSessionHolder.attach(collabListener);
        showHostInvite();
    }

    /** Shows (or re-shows) the QR code for the session this device is hosting. Re-encoded from the
     *  live token each time rather than held onto, so there is one source of truth for it. */
    private void showHostInvite() {
        if (collabSession == null) return;
        if (collabStatusDialog != null) collabStatusDialog.dismiss();
        Bitmap qr = QrCodes.encode(SessionCode.encode(collabSession.token()), dp(220));
        CollabDialogs.StatusDialog shown =
                CollabDialogs.showHostDialog(requireContext(), qr, this::endCollabSession);
        // "Done" puts the code away without ending anything, so the reference has to go with it —
        // otherwise the next roster change would be writing status into a dialog nobody can see.
        shown.dialog.setOnDismissListener(d -> {
            if (collabStatusDialog == shown) collabStatusDialog = null;
        });
        collabStatusDialog = shown;
        updateHostInviteStatus();
    }

    /** Keeps the hosting dialog's status line honest about who has already joined, since it now
     *  stays on screen while people arrive and leave. */
    private void updateHostInviteStatus() {
        if (collabStatusDialog == null || !isCollabHost) return;
        int connected = collabSession == null ? 0 : collabSession.currentPeers().size();
        collabStatusDialog.setStatus(connected == 0
                ? getString(R.string.collab_hosting_waiting)
                : getResources().getQuantityString(
                        R.plurals.collab_hosting_connected, connected, connected));
    }

    /**
     * Joining replaces this board with the host's, so anything already drawn here is about to go.
     *
     * <p>Offered as a choice rather than a warning to click past, because both answers are real
     * ones: the sketch was scrap, or it was work — and only the person who drew it knows which.
     * An empty board asks nothing; there is nothing to lose and no decision to make.
     */
    private void startJoinByScan() {
        if (whiteboardView == null || !whiteboardView.hasContent()) {
            scanForSession();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.collab_join_replaces_title)
                .setMessage(R.string.collab_join_replaces_message)
                .setPositiveButton(R.string.collab_join_save_copy, (d, w) -> saveCopyThenScan())
                .setNeutralButton(R.string.collab_join_discard, (d, w) -> scanForSession())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * Duplicates this board — strokes, text, background and title — into a new one on Home, and
     * then goes on to the scanner.
     *
     * <p>A copy rather than a move: the board being joined has to stay where it is, since it is
     * the one the session fills, and which of the two ends up holding the old drawing should not
     * be something the user has to reason about.
     */
    private void saveCopyThenScan() {
        final String sourceId = whiteboardId;
        final String typed = titleInput != null ? titleInput.getText().toString().trim() : "";
        final String copyTitle = typed.isEmpty()
                ? null : getString(R.string.collab_join_copy_name, typed);
        final Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            Whiteboard source = whiteboardRepo.getByIdSync(sourceId);
            List<Stroke> strokes = strokeDao.getByWhiteboard(sourceId);
            List<WhiteboardText> texts = textDao.getByWhiteboard(sourceId);
            int background = source != null
                    ? source.background : WhiteboardPreferences.defaultBackground(appContext);
            // The callback lands on the main thread, so the row copying gets a thread of its own.
            whiteboardRepo.createWhiteboard(copyTitle, null, background, copyId -> new Thread(() -> {
                // New ids: these rows are a second board now, not the same one twice.
                for (Stroke stroke : strokes) {
                    stroke.id = UUID.randomUUID().toString();
                    stroke.whiteboardId = copyId;
                    strokeDao.insertStroke(stroke);
                }
                for (WhiteboardText text : texts) {
                    text.id = UUID.randomUUID().toString();
                    text.whiteboardId = copyId;
                    textDao.insert(text);
                }
                AppExecutors.getInstance().mainThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), R.string.collab_join_copy_saved,
                            Toast.LENGTH_SHORT).show();
                    scanForSession();
                });
            }).start());
        }).start();
    }

    private void scanForSession() {
        SessionScanner.scan(requireContext(), new SessionScanner.Listener() {
            @Override public void onToken(String token) {
                joinWithToken(token);
            }

            @Override public void onCancelled() {
                // See HomeFragment: leaving the scanner is not a failure to report.
            }

            @Override public void onFailed(boolean notASession) {
                if (!isAdded()) return;
                showCollabError(notASession
                        ? R.string.collab_error_not_a_session
                        : R.string.collab_error_scanner, notASession);
            }
        });
    }

    /** Starts joining a session whose token is already in hand — from a scan here, from Home's
     *  own scan, or from a {@code quill://} link the phone's camera opened. */
    private void joinWithToken(String token) {
        if (!isAdded()) return;
        isCollabHost = false;
        collabStatusDialog = CollabDialogs.showJoiningDialog(requireContext(), this::endCollabSession);
        collabSession = CollabSessionHolder.join(requireContext(), token, myCollabDisplayName(), whiteboardId);
        // See startHosting: joining from Home happens before onStart, joining from this screen
        // happens long after it, and either way this listener has to be the one attached.
        CollabSessionHolder.attach(collabListener);
    }

    private final CollabSessionHolder.RosterListener collabListener = new CollabSessionHolder.RosterListener() {
        @Override
        public void onPeerConnected(String peerId) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), R.string.collab_connected, Toast.LENGTH_SHORT).show();
                applyCollabRoleToUi();
                updateCollabRoster();
                if (isCollabHost) {
                    // The code stays up: this session accepts joiners for as long as it runs, and
                    // dismissing it here is what used to make the first joiner the only one.
                    // Sending the board to this joiner is the session's job, not this screen's —
                    // see CollabSessionHolder.sendBoardTo.
                    updateHostInviteStatus();
                } else if (collabStatusDialog != null) {
                    // The joiner's dialog is a progress report, and the progress is over.
                    collabStatusDialog.setStatus(getString(R.string.collab_connected));
                    collabStatusDialog.dismiss();
                    collabStatusDialog = null;
                }
            });
        }

        @Override
        public void onPeerDisconnected(String peerId) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), R.string.collab_disconnected, Toast.LENGTH_SHORT).show();
                updateCollabRoster();
                // A host whose last joiner dropped keeps hosting — the code is still on screen and
                // still valid. Only a joiner has nothing left once the host is gone.
                if (isCollabHost) updateHostInviteStatus();
                else if (collabSession == null || !collabSession.hasAnyPeer()) endCollabSession();
                else applyCollabRoleToUi();
            });
        }

        @Override
        public void onPeerInfoUpdated(String peerId, String displayName) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                updateCollabRoster();
            });
        }

        @Override
        public void onPeerPresenceChanged(String peerId, boolean viewing) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                updateCollabRoster();
            });
        }

        @Override
        public void onPeerLeft(String peerId) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), R.string.collab_peer_left, Toast.LENGTH_SHORT).show();
                updateCollabRoster();
                applyCollabRoleToUi();
                updateHostInviteStatus();
            });
        }

        @Override
        public void onSessionEndedByHost() {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), R.string.collab_host_ended, Toast.LENGTH_SHORT).show();
                clearCollabLocalState();
            });
        }

        @Override
        public void onMessage(String peerId, CollabMessage message) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                applyIncoming(peerId, message);
            });
        }

        @Override
        public void onError(CollabSession.Failure failure, String detail) {
            android.util.Log.w("Whiteboard", "collab failed: " + failure + " (" + detail + ")");
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                boolean offerRetry = !isCollabHost && failure != CollabSession.Failure.RADIO_UNAVAILABLE;
                clearCollabLocalState();
                showCollabError(messageFor(failure), offerRetry);
            });
        }
    };

    /** One message per way this can go wrong — see {@link CollabSession.Failure}. */
    private int messageFor(CollabSession.Failure failure) {
        switch (failure) {
            case RADIO_UNAVAILABLE: return R.string.collab_error_radios;
            case CANNOT_HOST:       return R.string.collab_error_cannot_host;
            case CANNOT_SEARCH:     return R.string.collab_error_cannot_search;
            case SESSION_NOT_FOUND: return R.string.collab_error_not_found;
            case CONNECT_FAILED:
            default:                return R.string.collab_error_connect_failed;
        }
    }

    /**
     * A dialog rather than a toast: every one of these is a dead end the user has to decide
     * something about, and a message that fades after two seconds is not that.
     *
     * @param offerScanAgain adds a second button straight back to the scanner, for the failures
     *                       where trying the same code again is the obvious next move.
     */
    private void showCollabError(int messageRes, boolean offerScanAgain) {
        if (!isAdded()) return;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.collab_error_title)
                .setMessage(messageRes)
                .setPositiveButton(android.R.string.ok, null);
        if (offerScanAgain) {
            builder.setNeutralButton(R.string.collab_scan_again, (d, w) -> startJoinByScan());
        }
        builder.show();
    }

    /** Applies one message from a peer to this screen. Passing it on to the other peers is the
     *  host's job and happens in {@link CollabSessionHolder} before this is ever called, so that a
     *  host who has left the whiteboard is still the route between two joiners. */
    private void applyIncoming(String fromPeerId, CollabMessage message) {
        if (whiteboardView == null) return;
        switch (message.type) {
            case CollabMessage.TYPE_SNAPSHOT:
                if (!isCollabHost) applySnapshot(message);
                break;
            case CollabMessage.TYPE_STROKE:
                // The peer's whiteboard_id is *their* board's row, not this device's — each side
                // opened (or created) its own `whiteboards` row locally, so a stroke has to be
                // re-tagged onto this device's id before it can satisfy the strokes→whiteboards
                // foreign key. Left as the peer's own id, insertStroke throws
                // SQLITE_CONSTRAINT_FOREIGNKEY and crashes the process — which is exactly what a
                // real two-device run surfaced.
                message.stroke.whiteboardId = whiteboardId;
                whiteboardView.addStroke(message.stroke);
                new Thread(() -> strokeDao.insertStroke(message.stroke)).start();
                touchWhiteboard();
                break;
            case CollabMessage.TYPE_TEXT:
                message.text.whiteboardId = whiteboardId;
                whiteboardView.addText(message.text);
                new Thread(() -> textDao.insert(message.text)).start();
                touchWhiteboard();
                break;
            case CollabMessage.TYPE_RETRACT:
                if (message.retractIsText) {
                    whiteboardView.removeText(message.retractId);
                    new Thread(() -> textDao.delete(message.retractId)).start();
                } else {
                    whiteboardView.removeStroke(message.retractId);
                    new Thread(() -> strokeDao.deleteStroke(message.retractId)).start();
                }
                touchWhiteboard();
                break;
            case CollabMessage.TYPE_CLEAR:
                if (!isCollabHost) applyRemoteClear();
                break;
        }
    }

    /**
     * The host's whole board, replacing whatever this device had — the host is ground truth for a
     * session, so a joiner starts from exactly what the host sees rather than merging.
     *
     * <p>Arrives in numbered chunks (see {@link CollabMessage#snapshotChunks}), so nothing is drawn
     * until all of them are in: a half-applied snapshot is a board missing strokes, which is worse
     * than one that appears a moment later. Chunks are collected by index rather than by arrival
     * order, and a chunk announcing a different total means a fresh snapshot started — the older,
     * incomplete one is abandoned.
     */
    private void applySnapshot(CollabMessage message) {
        if (message.snapshotCount != pendingSnapshotCount) {
            pendingSnapshotChunks.clear();
            pendingSnapshotStrokes.clear();
            pendingSnapshotTexts.clear();
            pendingSnapshotCount = message.snapshotCount;
        }
        if (!pendingSnapshotChunks.add(message.snapshotIndex)) return; // a repeat; already have it
        pendingSnapshotStrokes.addAll(message.strokes);
        pendingSnapshotTexts.addAll(message.texts);
        if (pendingSnapshotChunks.size() < pendingSnapshotCount) return;

        List<Stroke> strokes = new ArrayList<>(pendingSnapshotStrokes);
        List<WhiteboardText> texts = new ArrayList<>(pendingSnapshotTexts);
        pendingSnapshotChunks.clear();
        pendingSnapshotStrokes.clear();
        pendingSnapshotTexts.clear();
        pendingSnapshotCount = 0;

        // Chunks can be reassembled in any order, so draw order is restored from the timestamps
        // rather than inherited from the wire — ink laid down later belongs on top.
        java.util.Collections.sort(strokes, (a, b) -> Long.compare(a.createdAt, b.createdAt));
        java.util.Collections.sort(texts, (a, b) -> Long.compare(a.createdAt, b.createdAt));

        // Same re-tagging as a live STROKE/TEXT message, and for the same reason: every item in
        // the host's snapshot still carries the host's own whiteboard_id.
        for (Stroke s : strokes) s.whiteboardId = whiteboardId;
        for (WhiteboardText t : texts) t.whiteboardId = whiteboardId;

        whiteboardView.clearAll();
        undoStack.clear();
        for (Stroke s : strokes) whiteboardView.addStroke(s);
        for (WhiteboardText t : texts) whiteboardView.addText(t);
        whiteboardView.centreOnContent();
        new Thread(() -> {
            strokeDao.deleteAllForWhiteboard(whiteboardId);
            textDao.deleteAllForWhiteboard(whiteboardId);
            for (Stroke s : strokes) strokeDao.insertStroke(s);
            for (WhiteboardText t : texts) textDao.insert(t);
        }).start();
    }

    /** Clear is host-only in a live session — a joiner sees the button disabled entirely, rather
     *  than tappable-but-rejected, so there's nothing to discover the hard way. */
    private void applyCollabRoleToUi() {
        if (btnClear != null) btnClear.setEnabled(collabSession == null || isCollabHost);
        if (btnCollab != null) {
            btnCollab.setSelected(collabSession != null);
            btnCollab.setContentDescription(getString(collabSession != null
                    ? R.string.collab_end_session : R.string.action_collaborate));
        }
        updateCollabRoster();
    }

    /**
     * Re-reads the roster and shows it as a count. Called on every roster event, cheaply — there
     * are at most a handful of peers, and deriving it each time is what keeps this screen from
     * having a second, staler idea of who is in the session.
     */
    private void updateCollabRoster() {
        if (collabPeople == null) return;
        if (collabSession == null) {
            collabPeople.setVisibility(View.GONE);
            return;
        }
        int count = collabRosterNames().size();
        collabPeople.setVisibility(View.VISIBLE);
        collabPeople.setText(String.valueOf(count));
        collabPeople.setContentDescription(
                getResources().getQuantityString(R.plurals.collab_people_count, count, count));
    }

    /**
     * Everyone at the board right now, this device first.
     *
     * <p>Only those actually looking at it: a session survives its screen, so someone who backed
     * out of the whiteboard is still connected and still relaying, but they are not there to draw
     * with, and listing them would be the roster telling a small lie every time.
     */
    private List<String> collabRosterNames() {
        List<String> names = new ArrayList<>();
        if (collabSession == null) return names;
        names.add(getString(R.string.collab_you, myCollabDisplayName()));
        for (CollabSession.PeerInfo peer : collabSession.currentPeers()) {
            if (!peer.viewing) continue;
            names.add(peer.displayName != null
                    ? peer.displayName : getString(R.string.collab_peer_connecting));
        }
        return names;
    }

    /** The names behind the count. A list, not a row of chips: it is read once, when someone
     *  wonders who else is here, and it costs the board nothing the rest of the time. */
    private void showCollabRoster() {
        List<String> names = collabRosterNames();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getResources().getQuantityString(
                        R.plurals.collab_people_count, names.size(), names.size()))
                .setItems(names.toArray(new String[0]), null)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * The back button / system back. Leaving the board leaves the session with it.
     *
     * <p>The session can technically outlive this screen, and briefly does — backgrounding the app
     * keeps it up, which is what {@code setViewing} is for. But walking out of the whiteboard is
     * not backgrounding: it is the gesture that means "I'm done here", and a session that quietly
     * kept running behind Home was one nobody could see, leave, or avoid being dragged back into
     * the next time they opened any board at all.
     *
     * <p>What survives is the board itself — every stroke received is already on this device, so
     * leaving keeps a copy rather than losing the work, and scanning the code again rejoins.
     */
    private void attemptExit() {
        if (collabSession == null) {
            androidx.navigation.fragment.NavHostFragment.findNavController(this).navigateUp();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.collab_exit_warning_title)
                .setMessage(isCollabHost
                        ? R.string.collab_exit_host_message
                        : R.string.collab_exit_joiner_message)
                .setPositiveButton(isCollabHost
                        ? R.string.collab_exit_confirm
                        : R.string.collab_leave_session, (d, w) -> {
                    endCollabSession();
                    androidx.navigation.fragment.NavHostFragment.findNavController(this).navigateUp();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Explicit exit from the session: ends it for everyone if this device is the host, or just
     *  removes this device if it's a joiner — see {@link CollabSessionHolder#end()}/
     *  {@link CollabSessionHolder#leave()}. */
    private void endCollabSession() {
        if (isCollabHost) CollabSessionHolder.end();
        else CollabSessionHolder.leave();
        clearCollabLocalState();
    }

    private void clearCollabLocalState() {
        collabSession = null;
        pendingSnapshotChunks.clear();
        pendingSnapshotStrokes.clear();
        pendingSnapshotTexts.clear();
        pendingSnapshotCount = 0;
        if (collabStatusDialog != null) {
            collabStatusDialog.dismiss();
            collabStatusDialog = null;
        }
        applyCollabRoleToUi();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
