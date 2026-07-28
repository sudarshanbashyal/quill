package mse.quill.ui.whiteboard;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.provider.MediaStore;
import android.content.ContentValues;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.fragment.app.Fragment;

import mse.quill.R;
import mse.quill.data.AppDatabase;
import mse.quill.data.StrokeDao;
import mse.quill.data.WhiteboardDao;
import mse.quill.model.Stroke;
import mse.quill.model.Whiteboard;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
 * and persists strokes to the local SQLite database via StrokeDao.
 *
 * Networking (Wi-Fi Direct / StrokeServer / StrokeClient) is intentionally
 * left out of this version — everything works fully offline on one device.
 * When you're ready to add collaboration, you re-introduce WiFiDirectManager
 * and call server.broadcast()/client.sendStroke() inside onStrokeComplete().
 *
 * Navigation args expected (set in nav_graph.xml, passed via Bundle):
 *   "note_id"        — String, required. The parent note this whiteboard belongs to.
 *   "whiteboard_id"  — String, optional. Pass this to reopen an existing whiteboard;
 *                       omit it to create a new one.
 */
public class WhiteboardFragment extends Fragment implements WhiteboardView.StrokeListener {

    private static final String TAG = "WhiteboardFragment";

    // ── Views ─────────────────────────────────────────────────────────────────
    private WhiteboardView whiteboardView;
    private TextView       statusText;
    private ImageButton    btnPen, btnEraser, btnHighlighter;
    private ImageButton    btnColorBlack, btnColorRed, btnColorBlue, btnColorGreen, btnColorYellow;
    private ImageButton    btnWidthThin, btnWidthMedium, btnWidthThick;
    private ImageButton    btnUndo, btnClear, btnExport;
    private View           colorIndicator;

    // ── Data ──────────────────────────────────────────────────────────────────
    private StrokeDao     strokeDao;
    private WhiteboardDao whiteboardDao;
    private String        whiteboardId;
    private String        noteId;
    private Stroke        lastStroke; // enables single-level undo
    private final Deque<String> undoStack = new ArrayDeque<>();
    // ── Fragment lifecycle ────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Read navigation arguments passed by whoever opened this screen
        Bundle args  = getArguments();
        noteId       = args != null ? args.getString("note_id")       : null;
        whiteboardId = args != null ? args.getString("whiteboard_id") : null;

        // 2. Get access to the database (singleton — safe to call anywhere)
        AppDatabase db = AppDatabase.getInstance(requireContext());
        strokeDao      = new StrokeDao(db);
        whiteboardDao  = new WhiteboardDao(db);

        // 3. If no whiteboard_id was passed, this is a brand-new whiteboard —
        //    generate an id and insert a row into the `whiteboards` table.
        if (whiteboardId == null) {
            whiteboardId  = UUID.randomUUID().toString();
            Whiteboard wb = new Whiteboard();
            wb.id         = whiteboardId;
            wb.noteId     = noteId;
            whiteboardDao.insert(wb);
        }
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

        // Load any strokes already saved for this whiteboard (e.g. reopening a note)
        // Runs on a background thread because SQLite reads should not block the UI thread.
        new Thread(() -> {
            List<Stroke> existing = strokeDao.getByWhiteboard(whiteboardId);
            requireActivity().runOnUiThread(() -> {
                if (whiteboardView == null) return;
                whiteboardView.loadStrokes(existing);

                // Rebuild the undo stack in the same order, so undo starts
                // from the most recently drawn stroke even after reopening.
                undoStack.clear();
                for (Stroke s : existing) undoStack.push(s.id);
            });
        }).start();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Null out view references to avoid holding onto a destroyed View
        whiteboardView = null;
        statusText     = null;
    }

    // ── View binding ──────────────────────────────────────────────────────────

    /** Finds every UI element declared in fragment_whiteboard.xml by its android:id. */
    private void bindViews(View root) {
        whiteboardView  = root.findViewById(R.id.whiteboardView);
        statusText      = root.findViewById(R.id.statusText);

        btnPen          = root.findViewById(R.id.btnPen);
        btnEraser       = root.findViewById(R.id.btnEraser);
        btnHighlighter  = root.findViewById(R.id.btnHighlighter);

        btnColorBlack   = root.findViewById(R.id.btnColorBlack);
        btnColorRed     = root.findViewById(R.id.btnColorRed);
        btnColorBlue    = root.findViewById(R.id.btnColorBlue);
        btnColorGreen   = root.findViewById(R.id.btnColorGreen);
        btnColorYellow  = root.findViewById(R.id.btnColorYellow);
        colorIndicator  = root.findViewById(R.id.colorIndicator);

        btnWidthThin    = root.findViewById(R.id.btnWidthThin);
        btnWidthMedium  = root.findViewById(R.id.btnWidthMedium);
        btnWidthThick   = root.findViewById(R.id.btnWidthThick);

        btnUndo         = root.findViewById(R.id.btnUndo);
        btnClear        = root.findViewById(R.id.btnClear);
        btnExport       = root.findViewById(R.id.btnExport);
    }

    /** Attaches click listeners to every toolbar button. */
    private void setupToolbar() {
        // Tool selection
        btnPen.setOnClickListener(v -> {
            whiteboardView.setTool(WhiteboardView.TOOL_PEN);
            highlightTool(btnPen);
        });
        btnEraser.setOnClickListener(v -> {
            whiteboardView.setTool(WhiteboardView.TOOL_ERASER);
            highlightTool(btnEraser);
        });
        btnHighlighter.setOnClickListener(v -> {
            whiteboardView.setTool(WhiteboardView.TOOL_HIGHLIGHTER);
            highlightTool(btnHighlighter);
        });

        // Color selection
        btnColorBlack.setOnClickListener(v  -> selectColor(Color.BLACK));
        btnColorRed.setOnClickListener(v    -> selectColor(Color.RED));
        btnColorBlue.setOnClickListener(v   -> selectColor(Color.BLUE));
        btnColorGreen.setOnClickListener(v  -> selectColor(Color.GREEN));
        btnColorYellow.setOnClickListener(v -> selectColor(Color.YELLOW));

        // Stroke width selection
        btnWidthThin.setOnClickListener(v   -> whiteboardView.setStrokeWidth(3f));
        btnWidthMedium.setOnClickListener(v -> whiteboardView.setStrokeWidth(7f));
        btnWidthThick.setOnClickListener(v  -> whiteboardView.setStrokeWidth(14f));

        // Actions
        btnUndo.setOnClickListener(v   -> undoLastStroke());
        btnClear.setOnClickListener(v  -> confirmClear());
        btnExport.setOnClickListener(v -> exportWhiteboard());

        // Set sensible defaults on screen open
        highlightTool(btnPen);
        selectColor(Color.BLACK);
        if (statusText != null) statusText.setText("Whiteboard (offline mode)");
    }

    private void highlightTool(ImageButton active) {
        btnPen.setSelected(false);
        btnEraser.setSelected(false);
        btnHighlighter.setSelected(false);
        active.setSelected(true);
    }

    private void selectColor(int color) {
        whiteboardView.setColor(color);
        if (colorIndicator != null) colorIndicator.setBackgroundColor(color);
    }

    // ── WhiteboardView.StrokeListener ─────────────────────────────────────────

    /**
     * Called automatically by WhiteboardView every time the user lifts
     * their finger after drawing a stroke. This is where persistence happens.
     */
    @Override
    public void onStrokeComplete(Stroke stroke) {
        stroke.whiteboardId = whiteboardId;
        undoStack.push(stroke.id);

        // Save to SQLite on a background thread (never touch DB on the UI thread)
        new Thread(() -> strokeDao.insertStroke(stroke)).start();
    }

    // ── Undo / Clear ──────────────────────────────────────────────────────────

    private void undoLastStroke() {
        if (undoStack.isEmpty()) {
            Toast.makeText(requireContext(), "Nothing to undo", Toast.LENGTH_SHORT).show();
            return;
        }
        String strokeId = undoStack.pop(); // removes and returns the top of the stack


        whiteboardView.removeStroke(strokeId);
        new Thread(() -> strokeDao.deleteStroke(strokeId)).start();
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
        new Thread(() -> strokeDao.deleteAllForWhiteboard(whiteboardId)).start();
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
        Toast.makeText(requireContext(), "Export failed", Toast.LENGTH_SHORT).show();
    }
}
