package mse.quill.ui.whiteboard;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.fragment.app.Fragment;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import mse.quill.R;
import mse.quill.collab.CollabMessage;
import mse.quill.collab.CollabSession;
import mse.quill.collab.QrCodes;
import mse.quill.data.AppDatabase;
import mse.quill.data.StrokeRepository;
import mse.quill.data.WhiteboardRepository;
import mse.quill.data.WhiteboardTextRepository;
import mse.quill.data.model.Stroke;
import mse.quill.data.model.Whiteboard;
import mse.quill.data.model.WhiteboardText;
import mse.quill.util.NoteDisplayUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
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
public class WhiteboardFragment extends Fragment implements WhiteboardView.StrokeListener {

    private static final String TAG = "WhiteboardFragment";

    public static final String ARG_NOTE_ID = "note_id";
    public static final String ARG_WHITEBOARD_ID = "whiteboard_id";

    // ── Views ─────────────────────────────────────────────────────────────────
    private WhiteboardView whiteboardView;
    private EditText       titleInput;
    private ImageButton    btnPen, btnEraser, btnHighlighter, btnMove, btnText;
    private EditText       textEditor;
    private ImageButton    btnColorBlack, btnColorRed, btnColorBlue, btnColorGreen, btnColorYellow;
    private ImageButton    btnWidthThin, btnWidthMedium, btnWidthThick, btnWidthExtraThick;
    private ImageButton    btnCentre, btnUndo, btnClear, btnExport, btnToggleTools;
    private ImageButton    btnBackground, btnCollab;
    private View           leftSidebar;

    // ── Live collaboration (Epic C) ──────────────────────────────────────────
    private CollabSession collabSession;
    private boolean isCollabHost;
    private CollabDialogs.StatusDialog collabStatusDialog;
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
        whiteboardView.setStrokeListener(this); // get notified when a stroke finishes
        whiteboardView.setTextPlacementListener(this::beginText);
        textEditor.setOnEditorActionListener((v, actionId, e) -> {
            commitText();
            return true;
        });

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

    @Override
    public void onPause() {
        super.onPause();
        commitText();
        saveTitle();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        endCollabSession();
        // Null out view references to avoid holding onto a destroyed View
        whiteboardView = null;
        titleInput     = null;
        textEditor     = null;
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

        root.findViewById(R.id.back_button).setOnClickListener(v ->
                androidx.navigation.fragment.NavHostFragment.findNavController(this).navigateUp());

        leftSidebar     = root.findViewById(R.id.leftSidebar);
        btnToggleTools  = root.findViewById(R.id.btnToggleTools);
        btnBackground   = root.findViewById(R.id.btnBackground);
        btnCentre       = root.findViewById(R.id.btnCentre);
        btnUndo         = root.findViewById(R.id.btnUndo);
        btnClear        = root.findViewById(R.id.btnClear);
        btnExport       = root.findViewById(R.id.btnExport);
        btnCollab       = root.findViewById(R.id.btnCollab);
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
        btnCentre.setOnClickListener(v -> whiteboardView.centreOnContent());
        btnUndo.setOnClickListener(v   -> undoLastStroke());
        btnClear.setOnClickListener(v  -> confirmClear());
        btnExport.setOnClickListener(this::showExportMenu);
        btnCollab.setOnClickListener(v -> showCollabEntry());

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

    private void undoLastStroke() {
        if (undoStack.isEmpty()) {
            Toast.makeText(requireContext(), "Nothing to undo", Toast.LENGTH_SHORT).show();
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

    // ── Live collaboration (Epic C) ──────────────────────────────────────────────

    /** "Host a session" / "Join a session" — the entry point for the whole feature. */
    private void showCollabEntry() {
        if (collabSession != null) {
            // Already in a session: the button becomes "end session" instead of opening the
            // choice again.
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.collab_end_session)
                    .setMessage(R.string.collab_leaving_locks_others_out)
                    .setPositiveButton(R.string.collab_end_session, (d, w) -> endCollabSession())
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
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
        List<String> missing = new ArrayList<>();
        for (String permission : collabPermissionsForThisDevice()) {
            if (ContextCompat.checkSelfPermission(requireContext(), permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) {
            action.run();
            return;
        }
        pendingCollabAction = action;
        collabPermissionLauncher.launch(missing.toArray(new String[0]));
    }

    private List<String> collabPermissionsForThisDevice() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT <= 32) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        return permissions;
    }

    private void startHosting() {
        isCollabHost = true;
        collabSession = CollabSession.host(requireContext(), collabListener);
        Bitmap qr = QrCodes.encode(collabSession.token(), dp(220));
        collabStatusDialog = CollabDialogs.showHostDialog(requireContext(), qr, this::endCollabSession);
    }

    private void startJoinByScan() {
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(requireContext(), options);
        scanner.startScan()
                .addOnSuccessListener(barcode -> {
                    String token = barcode.getRawValue();
                    if (token == null || token.isEmpty()) {
                        Toast.makeText(requireContext(), R.string.collab_scan_failed, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    isCollabHost = false;
                    collabStatusDialog = CollabDialogs.showJoiningDialog(requireContext(), this::endCollabSession);
                    collabSession = CollabSession.join(requireContext(), token, collabListener);
                })
                .addOnFailureListener(e ->
                        Toast.makeText(requireContext(), R.string.collab_scan_failed, Toast.LENGTH_SHORT).show());
    }

    private final CollabSession.Listener collabListener = new CollabSession.Listener() {
        @Override
        public void onPeerConnected() {
            requireActivity().runOnUiThread(() -> {
                if (collabStatusDialog != null) {
                    collabStatusDialog.setStatus(getString(R.string.collab_connected));
                    collabStatusDialog.dialog.dismiss();
                    collabStatusDialog = null;
                }
                Toast.makeText(requireContext(), R.string.collab_connected, Toast.LENGTH_SHORT).show();
                applyCollabRoleToUi();
                // The host is the source of truth for a device that just joined: send it
                // everything currently on the board, read fresh off disk rather than trusting
                // whatever the view happens to hold.
                if (isCollabHost) {
                    final String id = whiteboardId;
                    new Thread(() -> {
                        List<Stroke> strokes = strokeDao.getByWhiteboard(id);
                        List<WhiteboardText> texts = textDao.getByWhiteboard(id);
                        CollabSession session = collabSession;
                        if (session != null) session.send(CollabMessage.snapshot(strokes, texts));
                    }).start();
                }
            });
        }

        @Override
        public void onPeerDisconnected() {
            requireActivity().runOnUiThread(() -> {
                Toast.makeText(requireContext(), R.string.collab_disconnected, Toast.LENGTH_SHORT).show();
                endCollabSession();
            });
        }

        @Override
        public void onMessage(CollabMessage message) {
            requireActivity().runOnUiThread(() -> applyIncoming(message));
        }

        @Override
        public void onError(String reason) {
            requireActivity().runOnUiThread(() -> {
                Toast.makeText(requireContext(), reason, Toast.LENGTH_SHORT).show();
                endCollabSession();
            });
        }
    };

    /** Applies one message from the peer. Never re-broadcasts — a message already came from the
     *  one place a session has more than two devices worth of state (host ↔ this device). */
    private void applyIncoming(CollabMessage message) {
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

    /** The host's whole board, replacing whatever this device had — the host is ground truth for
     *  a session, so a joiner starts from exactly what the host sees rather than merging. */
    private void applySnapshot(CollabMessage message) {
        // Same re-tagging as a live STROKE/TEXT message, and for the same reason: every item in
        // the host's snapshot still carries the host's own whiteboard_id.
        for (Stroke s : message.strokes) s.whiteboardId = whiteboardId;
        for (WhiteboardText t : message.texts) t.whiteboardId = whiteboardId;

        whiteboardView.clearAll();
        undoStack.clear();
        for (Stroke s : message.strokes) whiteboardView.addStroke(s);
        for (WhiteboardText t : message.texts) whiteboardView.addText(t);
        whiteboardView.centreOnContent();
        new Thread(() -> {
            strokeDao.deleteAllForWhiteboard(whiteboardId);
            textDao.deleteAllForWhiteboard(whiteboardId);
            for (Stroke s : message.strokes) strokeDao.insertStroke(s);
            for (WhiteboardText t : message.texts) textDao.insert(t);
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
    }

    private void endCollabSession() {
        if (collabSession != null) {
            collabSession.stop();
            collabSession = null;
        }
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
