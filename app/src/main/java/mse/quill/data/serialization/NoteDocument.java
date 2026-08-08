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
import mse.quill.ui.notes.editor.model.WhiteboardSegment;

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
    /** The shape reserved for this in Epic D. Unlike the other two, its id names a whiteboard row
     *  rather than an asset row, so it resolves without the media registry. */
    private static final String KIND_WHITEBOARD = "whiteboard";

    /** A Q&A is a fenced block rather than a {@code quill://} line because, unlike an image, its
     *  content *is* text — formatted, multi-line, and belonging in the document rather than on a
     *  side table. The info string carries the block's id ({@code ```quill-qa:8f3a…}), which is what
     *  gives a Q&A an identity that outlives a reload — see {@link #QA_FENCE_LINE}. */
    private static final String QA_FENCE = "```quill-qa";
    private static final String FENCE_CLOSE = "```";

    /**
     * A whole line opening a Q&A block, capturing the id in its info string.
     *
     * <p>The id is what a flashcard's {@code source_segment_id} points at, so it has to survive the
     * document round trip: a block's runtime id is minted by the view and would otherwise be
     * regenerated every time the note is parsed back in, and a card's review history would follow
     * the block for exactly one session. Keeping it in the info string rather than on a side table
     * means it travels with the block through copy, export and hand-editing.
     *
     * <p>The id is optional so blocks written before this landed still parse — they simply get a
     * fresh id, the same as a newly typed block.
     */
    private static final Pattern QA_FENCE_LINE = Pattern.compile("^```quill-qa(?::([^\\s]+))?$");
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
            } else if (segment.type() == NoteSegment.TYPE_WHITEBOARD) {
                out.append("![whiteboard](quill://").append(KIND_WHITEBOARD).append('/')
                        .append(segment.id).append(')');
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
            if (EMBED_LINE.matcher(lines[i]).matches() || isQaFence(lines[i])) {
                out.append('\\');
            }
            out.append(lines[i]);
        }
    }

    private static void appendQa(QaSegment segment, StringBuilder out) {
        out.append(QA_FENCE);
        if (segment.id != null && !segment.id.isEmpty()) out.append(':').append(segment.id);
        out.append('\n');
        appendFenced(segment.question, out);
        out.append(QA_DIVIDER).append('\n');
        appendFenced(segment.answer, out);
        out.append(FENCE_CLOSE);
    }

    /** Inside the fence, a line that would close the block or read as the divider has to be
     *  escaped — the backslash is consumed again by {@link MarkdownSerializer} on the way back. */
    private static void appendFenced(Spanned content, StringBuilder out) {
        for (String line : MarkdownSerializer.toMarkdown(content).split("\n", -1)) {
            if (line.equals(FENCE_CLOSE) || line.equals(QA_DIVIDER) || isQaFence(line)) {
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

            Matcher qaFence = QA_FENCE_LINE.matcher(line);
            if (qaFence.matches()) {
                int close = indexOfFenceClose(lines, i + 1);
                if (hasPendingText) {
                    segments.add(new TextSegment(MarkdownSerializer.fromMarkdown(pendingText.toString())));
                    pendingText.setLength(0);
                    hasPendingText = false;
                }
                segments.add(readQa(lines, i + 1, close, qaFence.group(1)));
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

            NoteSegment embedded;
            if (KIND_WHITEBOARD.equals(embed.group(1))) {
                // A whiteboard needs no registry row to resolve — the id is the board itself.
                // Whether that board still exists is the segment view's problem, not the parser's.
                embedded = new WhiteboardSegment(embed.group(2));
            } else {
                embedded = mediaById.get(embed.group(2));
            }
            if (embedded == null) continue;

            if (hasPendingText) {
                segments.add(new TextSegment(MarkdownSerializer.fromMarkdown(pendingText.toString())));
                pendingText.setLength(0);
                hasPendingText = false;
            }
            segments.add(embedded);
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

    private static QaSegment readQa(String[] lines, int from, int close, String id) {
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

        QaSegment segment = new QaSegment(
                MarkdownSerializer.fromMarkdown(question.toString()),
                MarkdownSerializer.fromMarkdown(answer.toString()));
        // A block written before ids were stored keeps the fresh one the constructor minted; the
        // next save writes it into the document, so it only ever happens once.
        if (id != null && !id.isEmpty()) segment.id = id;
        return segment;
    }

    /** Whether a line opens a Q&A block — with or without an id in its info string. */
    private static boolean isQaFence(String line) {
        return QA_FENCE_LINE.matcher(line).matches();
    }

    // ── Rewriting ──────────────────────────────────────────────────────────

    /**
     * Repoints every embed in the document at a new asset id, dropping the ones with nowhere to go.
     *
     * <p>This is what makes an imported note a genuine copy rather than a shallow one. A shared
     * bundle carries the sender's asset ids, and reusing them would have two devices' notes
     * referencing the same registry keys — so {@link mse.quill.data.NoteImporter} mints its own and
     * rewrites the document to match. It lives here rather than in the importer because the embed
     * syntax is this class's, and a second copy of {@link #EMBED_LINE} elsewhere is a rule that
     * would drift.
     *
     * <p><b>An embed whose id isn't in the map is removed</b>, and that single rule covers both
     * reasons one wouldn't be. An image whose file didn't make it into the archive has no asset to
     * point at; a whiteboard embed has no board, since a bundle carries one note and boards are not
     * part of it. Leaving either in place would be harmless to the parser — it drops embeds it
     * can't resolve — but it would leave dead references in the stored document until the next
     * save happened to rewrite them.
     *
     * <p>Escaped lines are untouched: a user who typed something that looks like an embed has
     * ordinary text, which is exactly what the backslash means here.
     */
    public static String rewriteEmbedIds(String markdown, Map<String, String> newIdByOldId) {
        if (markdown == null || markdown.isEmpty()) return markdown;

        String[] lines = markdown.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean first = true;

        for (String line : lines) {
            Matcher embed = EMBED_LINE.matcher(line);
            if (embed.matches()) {
                String newId = newIdByOldId.get(embed.group(2));
                if (newId == null) continue;   // nothing to point at — drop the line entirely
                if (!first) out.append('\n');
                out.append(line, 0, embed.start(2)).append(newId)
                        .append(line, embed.end(2), line.length());
            } else {
                if (!first) out.append('\n');
                out.append(line);
            }
            first = false;
        }
        return out.toString();
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
            if (isQaFence(line) || line.equals(FENCE_CLOSE) || line.equals(QA_DIVIDER)) continue;
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
