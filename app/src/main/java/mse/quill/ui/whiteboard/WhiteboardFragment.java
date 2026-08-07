package mse.quill.ui.whiteboard;

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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.fragment.app.Fragment;

import mse.quill.R;
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
    private ImageButton    btnBackground;
    private View           leftSidebar;

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
        btnExport.setOnClickListener(v -> exportWhiteboard());

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
    }

    // ── Export ────────────────────────────────────────────────────────────────

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
}
