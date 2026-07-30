package mse.quill.data.serialization;

import android.text.Spanned;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mse.quill.ui.notes.editor.model.HeadingMarker;
import mse.quill.ui.notes.editor.model.NoteSegment;
import mse.quill.ui.notes.editor.model.QaSegment;
import mse.quill.ui.notes.editor.model.TextSegment;

/**
 * Converts a note's whole segment list to and from a single Markdown document — the note's source
 * of truth, stored in {@code notes.content_blob}.
 *
 * <p>Segments are no longer rows. Text is the document itself; an embed is one block-level line
 * referencing a row in {@code note_segments}, which survives purely as a media asset registry:
 *
 * <pre>
 * # Lecture notes
 * Some **bold** text.
 *
 * ![](quill://image/3f2a…)
 *
 * - a bullet
 * ![audio](quill://audio/9b1c…)
 * </pre>
 *
 * <p>The URI carries only the asset id, not the file path, so moving media on disk never
 * invalidates a document. Metadata Markdown link syntax has nowhere to put — an image's display
 * width, an audio clip's duration and transcript — lives on the asset row and is rejoined by id
 * on load. Exporting to portable Markdown is then a matter of rewriting {@code quill://} URIs to
 * relative file paths.
 *
 * <p>Splitting the document back into segments is unambiguous because embed lines are the only
 * delimiter: every run of consecutive non-embed lines is one text segment. That matches how the
 * editor produces segments in the first place (it only ever splits text around an embed), so a
 * document with two adjacent text segments isn't representable — and isn't reachable either.
 */
public final class NoteDocument {

    /** Matches a whole line that is an embed reference, capturing its kind and asset id. */
    private static final Pattern EMBED_LINE =
            Pattern.compile("^!\\[[^\\]]*]\\(quill://([a-z]+)/([^)]+)\\)$");

    private static final String KIND_IMAGE = "image";
    private static final String KIND_AUDIO = "audio";

    /** A Q&A is a fenced block rather than a {@code quill://} line because, unlike an image, its
     *  content *is* text — formatted, multi-line, and belonging in the document rather than on a
     *  side table. The info string stays extensible so a linked flashcard id can be appended later
     *  ({@code quill-qa:fc_8f3a}) without changing the shape. */
    private static final String QA_FENCE = "```quill-qa";
    private static final String FENCE_CLOSE = "```";
    /** Divides question from answer inside the fence. Chosen over per-line prefixes so both halves
     *  can be ordinary multi-line Markdown. */
    private static final String QA_DIVIDER = "---";

    private NoteDocument() {}

    // ── Encode ─────────────────────────────────────────────────────────────

    /** Serializes the editor's segments to the Markdown document stored for the note. */
    public static String toMarkdown(List<NoteSegment> segments) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) out.append('\n');
            NoteSegment segment = segments.get(i);

            if (segment instanceof TextSegment) {
                appendText(((TextSegment) segment).content, out);
            } else if (segment.type() == NoteSegment.TYPE_IMAGE) {
                out.append("![](quill://").append(KIND_IMAGE).append('/').append(segment.id).append(')');
            } else if (segment.type() == NoteSegment.TYPE_AUDIO) {
                out.append("![audio](quill://").append(KIND_AUDIO).append('/').append(segment.id).append(')');
            } else if (segment instanceof QaSegment) {
                appendQa((QaSegment) segment, out);
            }
        }
        return out.toString();
    }

    /**
     * Appends a text segment, escaping any line the user typed that would otherwise read back as
     * an embed reference. The leading backslash is consumed by {@link MarkdownSerializer}'s
     * escape handling on the way back in, so the line round-trips as the literal text it was.
     */
    private static void appendText(Spanned content, StringBuilder out) {
        String[] lines = MarkdownSerializer.toMarkdown(content).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            if (EMBED_LINE.matcher(lines[i]).matches() || lines[i].equals(QA_FENCE)) {
                out.append('\\');
            }
            out.append(lines[i]);
        }
    }

    private static void appendQa(QaSegment segment, StringBuilder out) {
        out.append(QA_FENCE).append('\n');
        appendFenced(segment.question, out);
        out.append(QA_DIVIDER).append('\n');
        appendFenced(segment.answer, out);
        out.append(FENCE_CLOSE);
    }

    /** Inside the fence, a line that would close the block or read as the divider has to be
     *  escaped — the backslash is consumed again by {@link MarkdownSerializer} on the way back. */
    private static void appendFenced(Spanned content, StringBuilder out) {
        for (String line : MarkdownSerializer.toMarkdown(content).split("\n", -1)) {
            if (line.equals(FENCE_CLOSE) || line.equals(QA_DIVIDER) || line.equals(QA_FENCE)) {
                out.append('\\');
            }
            out.append(line).append('\n');
        }
    }

    // ── Decode ─────────────────────────────────────────────────────────────

    /**
     * Rebuilds the editor's segment list from the document.
     *
     * @param mediaById image/audio segments from the asset registry, keyed by id. An embed whose
     *                  asset row is missing is dropped rather than rendered as broken text.
     */
    public static List<NoteSegment> fromMarkdown(String markdown, Map<String, NoteSegment> mediaById) {
        List<NoteSegment> segments = new ArrayList<>();
        if (markdown == null) return segments;

        String[] lines = markdown.split("\n", -1);
        StringBuilder pendingText = new StringBuilder();
        boolean hasPendingText = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (line.equals(QA_FENCE)) {
                int close = indexOfFenceClose(lines, i + 1);
                if (hasPendingText) {
                    segments.add(new TextSegment(MarkdownSerializer.fromMarkdown(pendingText.toString())));
                    pendingText.setLength(0);
                    hasPendingText = false;
                }
                segments.add(readQa(lines, i + 1, close));
                i = close;
                continue;
            }

            Matcher embed = EMBED_LINE.matcher(line);
            if (!embed.matches()) {
                if (hasPendingText) pendingText.append('\n');
                pendingText.append(line);
                hasPendingText = true;
                continue;
            }

            NoteSegment media = mediaById.get(embed.group(2));
            if (media == null) continue;

            if (hasPendingText) {
                segments.add(new TextSegment(MarkdownSerializer.fromMarkdown(pendingText.toString())));
                pendingText.setLength(0);
                hasPendingText = false;
            }
            segments.add(media);
        }

        if (hasPendingText) {
            segments.add(new TextSegment(MarkdownSerializer.fromMarkdown(pendingText.toString())));
        }
        return segments;
    }

    /** Exclusive end of the fence body: the index of the closing line, or past the last line if
     *  the document was truncated — an unterminated block is read to the end rather than dropping
     *  the user's text. */
    private static int indexOfFenceClose(String[] lines, int from) {
        for (int i = from; i < lines.length; i++) {
            if (lines[i].equals(FENCE_CLOSE)) return i;
        }
        return lines.length;
    }

    private static QaSegment readQa(String[] lines, int from, int close) {
        StringBuilder question = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        StringBuilder target = question;
        boolean dividerSeen = false;

        for (int i = from; i < close; i++) {
            if (!dividerSeen && lines[i].equals(QA_DIVIDER)) {
                dividerSeen = true;
                target = answer;
                continue;
            }
            if (target.length() > 0) target.append('\n');
            target.append(lines[i]);
        }

        return new QaSegment(
                MarkdownSerializer.fromMarkdown(question.toString()),
                MarkdownSerializer.fromMarkdown(answer.toString()));
    }

    // ── Projections ────────────────────────────────────────────────────────

    /**
     * Flattens the document to searchable/previewable plain text: embed lines drop out, block and
     * inline markers are stripped, and the editor's invisible heading markers never appear since
     * they only exist once the document has been decoded into spans.
     */
    public static String toPlainText(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";

        StringBuilder out = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            if (EMBED_LINE.matcher(line).matches()) continue;
            // A Q&A's question and answer are real note content and stay; only the block's
            // scaffolding is dropped, so search and previews can see inside a Q&A.
            if (line.equals(QA_FENCE) || line.equals(FENCE_CLOSE) || line.equals(QA_DIVIDER)) continue;
            if (out.length() > 0) out.append('\n');
            out.append(HeadingMarker.strip(MarkdownSerializer.fromMarkdown(line).toString()));
        }
        return out.toString();
    }

    /** First non-empty line of plain text, for note list previews. */
    public static String toPreview(String markdown) {
        for (String line : toPlainText(markdown).split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return "";
    }
}
