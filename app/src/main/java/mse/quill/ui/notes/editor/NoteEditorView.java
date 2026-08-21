package mse.quill.ui.notes.editor;

import android.content.Context;
import android.graphics.Rect;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import androidx.core.view.OneShotPreDrawListener;

import java.util.ArrayList;
import java.util.List;

import mse.quill.audio.ReadPlaylist;
import mse.quill.ui.notes.editor.model.AudioSegment;
import mse.quill.ui.notes.editor.model.ImageSegment;
import mse.quill.ui.notes.editor.model.NoteSegment;
import mse.quill.ui.notes.editor.model.QaSegment;
import mse.quill.ui.notes.editor.model.WhiteboardSegment;
import mse.quill.ui.notes.editor.model.TextSegment;
import mse.quill.ui.notes.editor.segment.AudioSegmentView;
import mse.quill.ui.notes.editor.segment.BaseSegmentView;
import mse.quill.ui.notes.editor.segment.ImageSegmentView;
import mse.quill.ui.notes.editor.segment.QASegmentView;
import mse.quill.ui.notes.editor.segment.WhiteboardSegmentView;
import mse.quill.ui.notes.editor.segment.TextSegmentView;

public class NoteEditorView extends LinearLayout implements BaseSegmentView.SegmentCallback {

    private final List<BaseSegmentView> segments = new ArrayList<>();

    public interface ContentChangeListener {
        void onContentChanged();
    }

    /** Separate from {@link ContentChangeListener} because a caret move is not an edit — it must
     *  not schedule an autosave, only refresh what the toolbar is showing. */
    public interface SelectionChangeListener {
        void onSelectionChanged();
    }

    /** Asks the host to copy an embedded file out to shared storage. */
    public interface MediaExportListener {
        void onExportRequested(String filePath, BaseSegmentView.ExportResult result);
    }

    private ContentChangeListener contentChangeListener;
    private SelectionChangeListener selectionChangeListener;
    private MediaExportListener mediaExportListener;
    private WhiteboardSegmentView.OpenListener whiteboardOpenListener;

    public NoteEditorView(Context context) {
        super(context);
        init();
    }

    public NoteEditorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        // Start with one empty text segment
        addTextSegment(new SpannableStringBuilder(""), -1);
    }

    public void setContentChangeListener(ContentChangeListener listener) {
        this.contentChangeListener = listener;
    }

    public void setSelectionChangeListener(SelectionChangeListener listener) {
        this.selectionChangeListener = listener;
    }

    public void setMediaExportListener(MediaExportListener listener) {
        this.mediaExportListener = listener;
    }

    /** Set by the host fragment: only it can navigate to the board an embed points at. */
    public void setWhiteboardOpenListener(WhiteboardSegmentView.OpenListener listener) {
        this.whiteboardOpenListener = listener;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    public void insertImageAfterFocused(String filePath) {
        int insertAt = splitFocusedTextForBlockInsert();
        addImageSegment(null, filePath, 0, insertAt);

        // The caret continues in the text segment the split left after the image.
        TextSegmentView trailing = (TextSegmentView) segments.get(insertAt + 1);
        focusOnceVisible(trailing, trailing::focusAtStart);
    }

    /** Attaches a board at the caret. The board itself already exists — this only points at it. */
    public void insertWhiteboardAfterFocused(String whiteboardId) {
        int insertAt = splitFocusedTextForBlockInsert();
        addWhiteboardSegment(whiteboardId, insertAt);

        TextSegmentView trailing = (TextSegmentView) segments.get(insertAt + 1);
        focusOnceVisible(trailing, trailing::focusAtStart);
    }

    public void insertAudioAfterFocused(String filePath, int durationMs) {
        int insertAt = splitFocusedTextForBlockInsert();
        addAudioSegment(null, filePath, durationMs, insertAt);

        TextSegmentView trailing = (TextSegmentView) segments.get(insertAt + 1);
        focusOnceVisible(trailing, trailing::focusAtStart);
    }

    /**
     * Inserts an empty Q&A block after whatever is focused, splitting the current text segment at
     * the caret exactly as an image or audio insert does — a Q&A is a block in the document, so it
     * behaves like one.
     */
    public void insertQaBlockAfterFocused() {
        int insertAt = splitFocusedTextForBlockInsert();
        addQaSegment(null, new SpannableStringBuilder(""), new SpannableStringBuilder(""), insertAt);
        QASegmentView block = (QASegmentView) segments.get(insertAt);
        focusOnceVisible(block, block::focusQuestion);
    }

    /**
     * Focuses a just-inserted block and scrolls it into view, but only on the first frame where it
     * actually has layout bounds.
     *
     * <p>Focusing it inline instead — which is what this used to do — asks the ScrollView to reveal
     * a child that has not been measured yet, so it scrolls to where a zero-sized child nominally
     * sits: the top of the note. Nothing corrected it afterwards either, because the keyboard is
     * already up when a block is inserted, so no inset change fired the editor's own reveal and the
     * block stayed behind the keyboard. Waiting for pre-draw means the block has real bounds.
     *
     * <p>The reveal is deliberately <em>not</em> immediate. Splitting the segment above moved that
     * field's caret, and a TextView brings its caret into view from its own pre-draw pass — which,
     * registered first, is already running as a smooth scroll by the time this one goes. An
     * immediate {@code scrollBy} lands correctly and is then simply animated away by that scroller
     * on the next draw. Asking for a non-immediate scroll routes through {@code smoothScrollBy},
     * which takes the in-flight scroller over instead of racing it.
     *
     * <p>The rectangle is the whole block rather than the caret line, so the block arrives fully on
     * screen above the keyboard rather than with just its first line peeking over it.
     */
    private void focusOnceVisible(BaseSegmentView block, Runnable focusAction) {
        OneShotPreDrawListener.add(block, () -> {
            focusAction.run();
            block.requestRectangleOnScreen(
                    new Rect(0, 0, block.getWidth(), block.getHeight()), false);
        });
    }

    /**
     * Splits the focused text segment at the caret and returns the index a block should be
     * inserted at, leaving a text segment after it so there is always somewhere to keep writing.
     *
     * <p>When the caret is inside another block rather than in prose — a Q&amp;A field, most often —
     * there is nothing to split, and the new block goes immediately after the one being edited.
     * That is the whole of "insert a second Q&amp;A block from inside the first": no nesting, no
     * making the user tap out into the paragraph below and back again.
     */
    private int splitFocusedTextForBlockInsert() {
        int focusedIndex = getFocusedSegmentIndex();
        if (focusedIndex < 0) {
            int insertAt = segments.size();
            addTextSegment(new SpannableStringBuilder(""), insertAt);
            return insertAt;
        }

        if (!(segments.get(focusedIndex) instanceof TextSegmentView)) {
            int insertAt = focusedIndex + 1;
            // Only when there isn't one already, or repeatedly adding blocks this way would leave
            // an empty paragraph stacked between every pair of them.
            boolean textFollows = insertAt < segments.size()
                    && segments.get(insertAt) instanceof TextSegmentView;
            if (!textFollows) addTextSegment(new SpannableStringBuilder(""), insertAt);
            return insertAt;
        }

        TextSegmentView textView = (TextSegmentView) segments.get(focusedIndex);
        android.widget.EditText editText = textView.getEditText();
        int cursor = Math.max(0, editText.getSelectionStart());

        SpannableStringBuilder before = new SpannableStringBuilder(
                editText.getText().subSequence(0, cursor));
        SpannableStringBuilder after = new SpannableStringBuilder(
                editText.getText().subSequence(cursor, editText.getText().length()));

        textView.setText(before);
        // setText drops the caret to 0, and this field still has focus — leave it there and the
        // editor scrolls back to the segment's first line, which on a long paragraph means the top
        // of the note. The caret belongs at the split point anyway.
        editText.setSelection(before.length());
        textView.clearBulletContinuation();

        int insertAt = focusedIndex + 1;
        addTextSegment(after, insertAt);
        return insertAt;
    }

    /** Focuses the top of the body — where the title field's "next" key should land. Falls through
     *  to {@link #focusEnd()} for a note that opens on a block, which has no line to start on. */
    public void focusBodyStart() {
        for (BaseSegmentView view : segments) {
            if (view instanceof TextSegmentView) {
                ((TextSegmentView) view).focusAtStart();
                return;
            }
        }
        focusEnd();
    }

    /** Focuses the end of the last segment — used when the user taps empty space below the
     *  content to keep writing, rather than having to hit an existing line precisely. */
    public void focusEnd() {
        if (segments.isEmpty()) return;
        // A note ending in a block (image, audio, Q&A) has nowhere to put the caret, so tapping
        // below it did nothing at all. Give it somewhere to go.
        if (!(segments.get(segments.size() - 1) instanceof TextSegmentView)) {
            addTextSegment(new SpannableStringBuilder(""), segments.size());
        }
        ((TextSegmentView) segments.get(segments.size() - 1)).focusAtEnd();
    }

    public void applyBoldToFocused() {
        RichTextField field = getFocusedField();
        if (field != null) field.applyBold();
    }

    public void applyItalicToFocused() {
        RichTextField field = getFocusedField();
        if (field != null) field.applyItalic();
    }

    public void applyUnderlineToFocused() {
        RichTextField field = getFocusedField();
        if (field != null) field.applyUnderline();
    }

    public void applyHeadingToFocused(int level) {
        RichTextField field = getFocusedField();
        if (field != null) field.applyHeading(level);
    }

    public void applyBulletListToFocused() {
        RichTextField field = getFocusedField();
        if (field != null) field.applyBulletList();
    }

    /**
     * The editable the caret is in, wherever it lives — a body segment's field or one of a Q&A
     * block's two. Resolved by asking the view tree for its focused descendant rather than by
     * walking the segment list, so a segment holding several fields needs no special case here.
     */
    public RichTextField getFocusedField() {
        View focused = findFocus();
        return focused instanceof RichTextField ? (RichTextField) focused : null;
    }

    /** Everything the toolbar needs: what's on, and what the focused field even offers. */
    public FormattingState getFormattingState() {
        RichTextField field = getFocusedField();
        if (field == null) return FormattingState.none();

        FormattingState state = new FormattingState();
        state.bold = field.isBoldActive();
        state.italic = field.isItalicActive();
        state.underline = field.isUnderlineActive();
        state.bullet = field.isBulletActive();
        state.headingLevel = field.currentHeadingLevel();
        state.headingsAllowed = field.areHeadingsAllowed();
        // Always, wherever the caret is. A block cannot be nested *inside* a Q&A block, but it can
        // sit after one, and that is what inserting from in there now does — see
        // splitFocusedTextForBlockInsert. Refusing the control instead made the user tap out of the
        // block first to say the same thing.
        state.embedsAllowed = true;
        return state;
    }

    public List<BaseSegmentView> getSegments() {
        return segments;
    }

    /** Replaces all current segments with the given persisted segments, in order. */
    public void loadSegments(List<NoteSegment> loaded) {
        for (int i = segments.size() - 1; i >= 0; i--) {
            removeSegment(i);
        }

        if (loaded == null || loaded.isEmpty()) {
            addTextSegment(new SpannableStringBuilder(""), -1);
            return;
        }

        for (NoteSegment segment : loaded) {
            if (segment instanceof ImageSegment) {
                ImageSegment image = (ImageSegment) segment;
                addImageSegment(image.id, image.filePath, image.displayWidth, segments.size());
            } else if (segment instanceof AudioSegment) {
                AudioSegment audio = (AudioSegment) segment;
                addAudioSegment(audio.id, audio.filePath, audio.durationMs, segments.size());
            } else if (segment instanceof WhiteboardSegment) {
                addWhiteboardSegment(segment.id, segments.size());
            } else if (segment instanceof QaSegment) {
                QaSegment qa = (QaSegment) segment;
                addQaSegment(qa.id, qa.question, qa.answer, segments.size());
            } else if (segment instanceof TextSegment) {
                addTextSegment(new SpannableStringBuilder(((TextSegment) segment).content), segments.size());
            }
        }
    }

    /** Snapshots the current segments (independent of the live views) for persistence. Order is
     *  carried by the list itself — it becomes the order of blocks in the note's Markdown. */
    public List<NoteSegment> exportSegments() {
        List<NoteSegment> exported = new ArrayList<>();
        for (BaseSegmentView view : segments) {
            NoteSegment segment;
            if (view.getSegmentType() == NoteSegment.TYPE_IMAGE) {
                ImageSegmentView imageView = (ImageSegmentView) view;
                ImageSegment image = new ImageSegment(imageView.getFilePath());
                image.displayWidth = imageView.getDisplayWidth();
                segment = image;
            } else if (view.getSegmentType() == NoteSegment.TYPE_AUDIO) {
                AudioSegmentView audioView = (AudioSegmentView) view;
                segment = new AudioSegment(audioView.getFilePath(), audioView.getDurationMs());
            } else if (view.getSegmentType() == NoteSegment.TYPE_WHITEBOARD) {
                segment = new WhiteboardSegment(((WhiteboardSegmentView) view).getWhiteboardId());
            } else if (view.getSegmentType() == NoteSegment.TYPE_QA) {
                QASegmentView qaView = (QASegmentView) view;
                segment = new QaSegment(
                        new SpannableStringBuilder(qaView.getQuestion()),
                        new SpannableStringBuilder(qaView.getAnswer()));
            } else {
                SpannableStringBuilder snapshot = new SpannableStringBuilder((CharSequence) view.getSegmentData());
                segment = new TextSegment(snapshot);
            }
            segment.id = view.getSegmentId();
            exported.add(segment);
        }
        return exported;
    }

    /** Names every recording in this note after the note itself. A clip carries no name of its own,
     *  and once playback follows the user out of the editor the pill and the lock screen have to
     *  call it something — "the audio from Lecture 7" is the only thing that means anything. */
    public void setAudioClipTitle(String noteTitle) {
        for (BaseSegmentView view : segments) {
            if (view instanceof AudioSegmentView) ((AudioSegmentView) view).setClipTitle(noteTitle);
        }
    }

    /**
     * The note as read-aloud hears it: the words to speak and the recordings to play, in the order
     * they appear on screen. Images and whiteboards have nothing to say and don't appear.
     *
     * <p>Built from the views rather than from {@link #exportSegments()} so it costs no copies —
     * it is asked for on every keystroke, to decide whether a reading still has anything left to
     * read.
     */
    public ReadPlaylist buildReadPlaylist() {
        ReadPlaylist.Builder playlist = ReadPlaylist.builder();
        for (BaseSegmentView view : segments) {
            if (view instanceof TextSegmentView) {
                playlist.addText(((TextSegmentView) view).getText());
            } else if (view instanceof QASegmentView) {
                // Read a Q&A as the pair it is, so listening to a note doesn't silently skip it.
                QASegmentView qa = (QASegmentView) view;
                playlist.addText(qa.getQuestion());
                playlist.addText(qa.getAnswer());
            } else if (view instanceof AudioSegmentView) {
                AudioSegmentView audio = (AudioSegmentView) view;
                playlist.addClip(audio.getFilePath(), audio.getDurationMs());
            }
        }
        return playlist.build();
    }

    // ── SegmentCallback ────────────────────────────────────────────────────

    @Override
    public void onRequestSplitAt(BaseSegmentView segment, int cursorPosition) {
        // Tap on image → insert a new text segment after it
        int index = segments.indexOf(segment);
        if (index >= 0) {
            addTextSegment(new SpannableStringBuilder(""), index + 1);
            ((TextSegmentView) segments.get(index + 1)).focusAtStart();
        }
    }

    @Override
    public void onRequestDelete(BaseSegmentView segment) {
        int index = segments.indexOf(segment);
        if (index < 0) return;

        // Never delete the last segment if it's the only one
        if (segments.size() == 1) return;

        removeSegment(index);

        // Focus adjacent segment
        int focusIndex = Math.max(0, index - 1);
        if (segments.get(focusIndex) instanceof TextSegmentView) {
            ((TextSegmentView) segments.get(focusIndex)).focusAtEnd();
        }
    }

    @Override
    public void onRequestMergeWithPrevious(BaseSegmentView segment) {
        int index = segments.indexOf(segment);
        if (index <= 0) return; // nothing before it

        BaseSegmentView previous = segments.get(index - 1);

        if (previous instanceof TextSegmentView && segment instanceof TextSegmentView) {
            // Merge two text segments
            TextSegmentView prevText = (TextSegmentView) previous;
            TextSegmentView currText = (TextSegmentView) segment;

            int mergePoint = prevText.getText().length();

            SpannableStringBuilder merged = new SpannableStringBuilder();
            merged.append(prevText.getText());
            merged.append(currText.getText());

            prevText.setText(merged);
            prevText.focusAt(mergePoint);

            removeSegment(index);

        }
        // Backspacing into a block (image, audio, Q&A) deliberately does nothing. It used to
        // delete it, which meant a single keypress on the line below could silently destroy a
        // photo or a typed-out question — with no confirmation and no undo. Blocks are removed
        // only by long-pressing them, which asks first.
    }

    @Override
    public void onContentChanged() {
        if (contentChangeListener != null) contentChangeListener.onContentChanged();
    }

    @Override
    public void onSelectionChanged() {
        if (selectionChangeListener != null) selectionChangeListener.onSelectionChanged();
    }

    @Override
    public void onRequestExport(BaseSegmentView segment, BaseSegmentView.ExportResult result) {
        if (mediaExportListener == null) {
            result.onExportFinished(false);
            return;
        }
        if (segment instanceof ImageSegmentView) {
            mediaExportListener.onExportRequested(((ImageSegmentView) segment).getFilePath(), result);
        } else if (segment instanceof AudioSegmentView) {
            mediaExportListener.onExportRequested(((AudioSegmentView) segment).getFilePath(), result);
        } else {
            result.onExportFinished(false);
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private void addTextSegment(SpannableStringBuilder content, int index) {
        TextSegmentView view = new TextSegmentView(getContext());
        view.setText(content);
        view.setCallback(this);
        insertSegment(view, index);
    }

    /** segmentId null for a freshly inserted embed — the view mints one and it stays stable from
     *  then on, so the note's Markdown keeps referencing the same asset row across saves. */
    private void addImageSegment(String segmentId, String filePath, int displayWidth, int index) {
        ImageSegmentView view = new ImageSegmentView(getContext(), segmentId, filePath, displayWidth);
        view.setCallback(this);
        insertSegment(view, index);
    }

    private void addWhiteboardSegment(String whiteboardId, int index) {
        WhiteboardSegmentView view = new WhiteboardSegmentView(getContext(), whiteboardId);
        view.setCallback(this);
        view.setOpenListener(id -> {
            if (whiteboardOpenListener != null) whiteboardOpenListener.onOpenWhiteboard(id);
        });
        insertSegment(view, index);
    }

    private void addAudioSegment(String segmentId, String filePath, int durationMs, int index) {
        AudioSegmentView view = new AudioSegmentView(getContext(), segmentId, filePath, durationMs);
        view.setCallback(this);
        insertSegment(view, index);
    }

    private void addQaSegment(String segmentId, Spannable question, Spannable answer, int index) {
        QASegmentView view = new QASegmentView(getContext(), segmentId);
        view.setContent(question, answer);
        view.setCallback(this);
        insertSegment(view, index);
    }

    /** Re-points the body hint at whatever is now the last text segment. Called from the two
     *  places the segment list can change shape, so no insert/remove path can forget it. */
    private void updateHints() {
        for (int i = 0; i < segments.size(); i++) {
            BaseSegmentView view = segments.get(i);
            if (view instanceof TextSegmentView) {
                ((TextSegmentView) view).setHintVisible(i == segments.size() - 1);
            }
        }
    }

    private void insertSegment(BaseSegmentView view, int index) {
        if (index < 0 || index >= segments.size()) {
            segments.add(view);
            addView(view);
        } else {
            segments.add(index, view);
            addView(view, index);
        }
        updateHints();
    }

    private void removeSegment(int index) {
        BaseSegmentView view = segments.remove(index);
        if (view instanceof AudioSegmentView) ((AudioSegmentView) view).stopIfPlaying();
        removeView(view);
        updateHints();
    }

    private int getFocusedSegmentIndex() {
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).hasFocus()) return i;
        }
        return segments.size() - 1; // default to last
    }

}