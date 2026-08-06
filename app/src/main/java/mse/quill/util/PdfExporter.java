package mse.quill.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import mse.quill.ui.audio.PlaybackTime;
import mse.quill.ui.notes.editor.model.AudioSegment;
import mse.quill.ui.notes.editor.model.HeadingMarker;
import mse.quill.ui.notes.editor.model.ImageSegment;
import mse.quill.ui.notes.editor.model.NoteSegment;
import mse.quill.ui.notes.editor.model.QaSegment;
import mse.quill.ui.notes.editor.model.TextSegment;

/**
 * A note as a paginated PDF that still looks like the note.
 *
 * <p>Styling survives because the note's text is already a {@link Spanned} and {@link StaticLayout}
 * draws spans natively — bold, italic, underline and bullets need no translation at all. Only
 * headings do: the editor stores them as invisible line markers and derives the size and weight at
 * display time (see {@code RichTextField}), so this re-derives the same spans at the same scales.
 * Getting that wrong shows up as an H1 rendering at body size, not as a crash.
 *
 * <p>Audio is the one thing a page cannot hold. It becomes a labelled block naming the clip's
 * length, which is the part of a recording a reader can still act on — they know there is audio in
 * the original and how much of it.
 *
 * <p>Coordinates are PostScript points at 72dpi, so A4 is 595×842 and a text size of 11 means 11pt
 * on paper. Nothing here is in dp: a PDF has no screen density, and using one would make the export
 * depend on the device that produced it.
 */
public final class PdfExporter {

    public static final String EXTENSION = "pdf";
    public static final String MIME_TYPE = "application/pdf";

    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN = 54;
    private static final int CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;
    private static final int CONTENT_BOTTOM = PAGE_HEIGHT - MARGIN;

    private static final float TITLE_SIZE = 20f;
    private static final float BODY_SIZE = 11f;
    /** Matches {@code RichTextField}'s heading scales, so a heading is the same size relative to
     *  body text on paper as it is on screen. */
    private static final float HEADING_1_SCALE = 1.6f;
    private static final float HEADING_2_SCALE = 1.3f;

    private static final int BLOCK_GAP = 14;
    private static final int AUDIO_BLOCK_PADDING = 10;
    private static final int AUDIO_BLOCK_RADIUS = 8;
    private static final int QA_RULE_INSET = 10;

    private static final int TEXT_COLOR = Color.parseColor("#1C1B1F");
    private static final int MUTED_COLOR = Color.parseColor("#6B6B73");
    private static final int BLOCK_FILL = Color.parseColor("#F2F1F6");
    private static final int ACCENT_COLOR = Color.parseColor("#6C4EE3");

    /** Largest edge an embedded image is decoded at. Well past what fits on a page at 72dpi, so
     *  print quality is kept, while still refusing to pull a 50MP original into memory. */
    private static final int MAX_IMAGE_EDGE = 1600;

    private final PdfDocument document = new PdfDocument();
    private final String audioLabelFormat;

    private PdfDocument.Page page;
    private Canvas canvas;
    private int cursorY;
    private int pageNumber;

    private PdfExporter(String audioLabelFormat) {
        this.audioLabelFormat = audioLabelFormat;
    }

    /**
     * Renders a note and writes it out.
     *
     * @param audioLabelFormat localised {@code "Embedded Audio Recording - %1$s"}; the caller
     *                         resolves it so this class needs no {@code Context}.
     */
    public static void write(String title, List<NoteSegment> segments, String audioLabelFormat,
                             OutputStream out) throws IOException {
        PdfExporter exporter = new PdfExporter(audioLabelFormat);
        try {
            exporter.render(title, segments);
            exporter.document.writeTo(out);
        } finally {
            exporter.document.close();
        }
    }

    private void render(String title, List<NoteSegment> segments) {
        startPage();

        if (title != null && !title.trim().isEmpty()) {
            TextPaint paint = paint(TITLE_SIZE, Typeface.BOLD, TEXT_COLOR);
            drawLayout(layout(new SpannableStringBuilder(title.trim()), paint, CONTENT_WIDTH));
            cursorY += BLOCK_GAP;
        }

        for (NoteSegment segment : segments) {
            if (segment instanceof TextSegment) {
                drawText((TextSegment) segment);
            } else if (segment instanceof ImageSegment) {
                drawImage((ImageSegment) segment);
            } else if (segment instanceof AudioSegment) {
                drawAudio((AudioSegment) segment);
            } else if (segment instanceof QaSegment) {
                drawQa((QaSegment) segment);
            }
        }

        finishPage();
    }

    // ── Segments ───────────────────────────────────────────────────────────

    private void drawText(TextSegment segment) {
        if (segment.content == null || segment.content.length() == 0) return;
        Spanned styled = withHeadingStyling(segment.content);
        drawLayout(layout(styled, paint(BODY_SIZE, Typeface.NORMAL, TEXT_COLOR), CONTENT_WIDTH));
        cursorY += BLOCK_GAP;
    }

    /**
     * A recording, as the only thing a page can say about one: that it is there, and how long.
     *
     * <p>Drawn as a filled block rather than a line of prose so it reads as an object in the note,
     * the way the audio card does on screen — a reader skimming the PDF should see the same shape
     * of document.
     */
    private void drawAudio(AudioSegment segment) {
        String label = String.format(audioLabelFormat, PlaybackTime.format(segment.durationMs));
        TextPaint text = paint(BODY_SIZE, Typeface.ITALIC, MUTED_COLOR);
        StaticLayout inner = layout(new SpannableStringBuilder(label), text,
                CONTENT_WIDTH - 2 * AUDIO_BLOCK_PADDING);

        int blockHeight = inner.getHeight() + 2 * AUDIO_BLOCK_PADDING;
        // Never split the block across a page break: a label and its box on different sheets reads
        // as a rendering fault rather than as one element.
        ensureRoom(blockHeight);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(BLOCK_FILL);
        canvas.drawRoundRect(
                new RectF(MARGIN, cursorY, MARGIN + CONTENT_WIDTH, cursorY + blockHeight),
                AUDIO_BLOCK_RADIUS, AUDIO_BLOCK_RADIUS, fill);

        canvas.save();
        canvas.translate(MARGIN + AUDIO_BLOCK_PADDING, cursorY + AUDIO_BLOCK_PADDING);
        inner.draw(canvas);
        canvas.restore();

        cursorY += blockHeight + BLOCK_GAP;
    }

    private void drawImage(ImageSegment segment) {
        Bitmap bitmap = decode(segment.filePath);
        if (bitmap == null) return;

        float scale = CONTENT_WIDTH / (float) bitmap.getWidth();
        int width = CONTENT_WIDTH;
        int height = Math.round(bitmap.getHeight() * scale);

        // A tall image would otherwise be taller than any page and never find room.
        int maxHeight = CONTENT_BOTTOM - MARGIN;
        if (height > maxHeight) {
            width = Math.round(width * (maxHeight / (float) height));
            height = maxHeight;
        }
        ensureRoom(height);

        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(bitmap,
                new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()),
                new RectF(MARGIN, cursorY, MARGIN + width, cursorY + height), paint);
        bitmap.recycle();

        cursorY += height + BLOCK_GAP;
    }

    /** Question and answer, marked by a rule down the side — the block's identity on screen is its
     *  card, and a border is the closest thing a page has to one. */
    private void drawQa(QaSegment segment) {
        int textWidth = CONTENT_WIDTH - QA_RULE_INSET;
        StaticLayout question = layout(prefixed("Q  ", segment.question),
                paint(BODY_SIZE, Typeface.NORMAL, TEXT_COLOR), textWidth);
        StaticLayout answer = layout(prefixed("A  ", segment.answer),
                paint(BODY_SIZE, Typeface.NORMAL, MUTED_COLOR), textWidth);

        drawRuled(question);
        drawRuled(answer);
        cursorY += BLOCK_GAP;
    }

    /** Draws one layout indented past a vertical accent rule, page break by page break — the rule
     *  is redrawn per page so a block that spans a break keeps its marker on both. */
    private void drawRuled(StaticLayout layout) {
        Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
        rule.setColor(ACCENT_COLOR);
        rule.setStrokeWidth(2f);

        int line = 0;
        while (line < layout.getLineCount()) {
            int drawnFrom = cursorY;
            line = drawLines(layout, line, MARGIN + QA_RULE_INSET);
            canvas.drawLine(MARGIN + 1, drawnFrom, MARGIN + 1, cursorY, rule);
            if (line < layout.getLineCount()) newPage();
        }
    }

    // ── Text layout and pagination ─────────────────────────────────────────

    /**
     * Resolves the editor's invisible heading markers into real size and weight spans.
     *
     * <p>Copying into a {@link SpannableStringBuilder} first is what makes deleting the markers
     * safe: it carries every existing span across and then moves them itself as characters are
     * removed, so bold that started after a heading marker still starts on the same character.
     */
    private static Spanned withHeadingStyling(Spanned content) {
        SpannableStringBuilder out = new SpannableStringBuilder(content);
        int lineStart = 0;
        while (lineStart <= out.length()) {
            int lineEnd = lineStart;
            while (lineEnd < out.length() && out.charAt(lineEnd) != '\n') lineEnd++;

            int level = HeadingMarker.levelOf(out.subSequence(lineStart, lineEnd));
            if (level != HeadingMarker.NONE) {
                int markerLength = HeadingMarker.length(level);
                out.delete(lineStart, lineStart + markerLength);
                lineEnd -= markerLength;
                if (lineEnd > lineStart) {
                    float scale = level == HeadingMarker.H1 ? HEADING_1_SCALE : HEADING_2_SCALE;
                    out.setSpan(new RelativeSizeSpan(scale), lineStart, lineEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new StyleSpan(Typeface.BOLD), lineStart, lineEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }

            if (lineEnd >= out.length()) break;
            lineStart = lineEnd + 1;
        }
        return out;
    }

    private static SpannableStringBuilder prefixed(String prefix, Spanned content) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        out.append(prefix);
        out.setSpan(new StyleSpan(Typeface.BOLD), 0, prefix.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append(withHeadingStyling(content));
        return out;
    }

    private static TextPaint paint(float size, int style, int color) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, style));
        return paint;
    }

    private static StaticLayout layout(CharSequence text, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1f)
                .setIncludePad(false)
                .build();
    }

    private void drawLayout(StaticLayout layout) {
        int line = 0;
        while (line < layout.getLineCount()) {
            line = drawLines(layout, line, MARGIN);
            if (line < layout.getLineCount()) newPage();
        }
    }

    /**
     * Draws as many of {@code layout}'s lines from {@code fromLine} as fit below the cursor.
     *
     * <p>Rather than drawing line by line, the whole layout is drawn once with the canvas
     * translated so the first wanted line lands at the cursor, and clipped to the lines that fit.
     * That keeps every span, bullet gutter and wrap decision exactly as measured — re-laying out a
     * slice of the text would re-wrap it.
     *
     * @return the first line not drawn.
     */
    private int drawLines(StaticLayout layout, int fromLine, int left) {
        int top = layout.getLineTop(fromLine);
        int available = CONTENT_BOTTOM - cursorY;

        int endLine = fromLine;
        while (endLine < layout.getLineCount()
                && layout.getLineBottom(endLine) - top <= available) {
            endLine++;
        }

        // Not even one line fits — unless the page is already empty, in which case the line is
        // taller than a page and drawing it anyway beats looping forever.
        if (endLine == fromLine) {
            if (cursorY > MARGIN) {
                newPage();
                return drawLines(layout, fromLine, left);
            }
            endLine = fromLine + 1;
        }

        int bottom = layout.getLineTop(endLine);
        canvas.save();
        canvas.translate(left, cursorY - top);
        canvas.clipRect(0, top, layout.getWidth(), bottom);
        layout.draw(canvas);
        canvas.restore();

        cursorY += bottom - top;
        return endLine;
    }

    // ── Pages ──────────────────────────────────────────────────────────────

    private void ensureRoom(int height) {
        if (cursorY + height > CONTENT_BOTTOM && cursorY > MARGIN) newPage();
    }

    private void startPage() {
        pageNumber++;
        page = document.startPage(
                new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create());
        canvas = page.getCanvas();
        cursorY = MARGIN;
    }

    private void finishPage() {
        if (page == null) return;
        document.finishPage(page);
        page = null;
        canvas = null;
    }

    private void newPage() {
        finishPage();
        startPage();
    }

    // ── Images ─────────────────────────────────────────────────────────────

    private static Bitmap decode(String path) {
        if (path == null) return null;
        File file = new File(path);
        if (!file.exists()) return null;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        return BitmapFactory.decodeFile(path, options);
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        while (Math.max(width, height) / sample > MAX_IMAGE_EDGE) sample *= 2;
        return sample;
    }
}
