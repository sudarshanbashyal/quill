package mse.quill.util;

import java.util.List;

import mse.quill.data.serialization.MarkdownSerializer;
import mse.quill.ui.audio.PlaybackTime;
import mse.quill.data.model.AudioSegment;
import mse.quill.data.model.NoteSegment;
import mse.quill.data.model.QaSegment;
import mse.quill.data.model.TextSegment;

/**
 * A note as a Markdown file anything can open.
 *
 * <p>Deliberately thin: the note already *is* Markdown, so text passes through
 * {@link MarkdownSerializer} untouched and the work here is only the parts of Quill's document that
 * are Quill's rather than Markdown's.
 *
 * <p>Three of those, and each is resolved toward "readable elsewhere" rather than "reloadable
 * here". This is an export, not a backup — the note in the database is the copy that round-trips.
 * <ul>
 *   <li><b>Embeds.</b> {@code ![audio](quill://audio/<id>)} means nothing outside the app and the
 *       file it points at isn't in this document, so both kinds become an italic placeholder line.
 *       Audio names its length, matching the PDF.</li>
 *   <li><b>Q&amp;A blocks.</b> The {@code ```quill-qa} fence is a private construct; a reader that
 *       doesn't know it would show the id and the {@code ---} divider as literal text. They become
 *       bold <b>Q:</b>/<b>A:</b> paragraphs.</li>
 *   <li><b>The title.</b> Not part of the document at all — it lives in the note's row — so it is
 *       prepended as an H1.</li>
 * </ul>
 */
public final class MarkdownExporter {

    public static final String EXTENSION = "md";
    public static final String MIME_TYPE = "text/markdown";

    private MarkdownExporter() {}

    /**
     * @param audioLabel localised {@code "Embedded Audio Recording - %1$s"}, already resolved by
     *                   the caller — this class stays off {@code Context} so it can be unit-tested.
     * @param imageLabel localised stand-in for an image that can't travel in a text file.
     */
    public static String toMarkdown(String title, List<NoteSegment> segments,
                                    String audioLabel, String imageLabel) {
        StringBuilder out = new StringBuilder();
        if (title != null && !title.trim().isEmpty()) {
            out.append("# ").append(title.trim()).append("\n\n");
        }

        for (int i = 0; i < segments.size(); i++) {
            NoteSegment segment = segments.get(i);
            if (i > 0) out.append("\n\n");

            if (segment instanceof TextSegment) {
                out.append(MarkdownSerializer.toMarkdown(((TextSegment) segment).content));
            } else if (segment instanceof AudioSegment) {
                out.append(italic(String.format(audioLabel,
                        PlaybackTime.format(((AudioSegment) segment).durationMs))));
            } else if (segment.type() == NoteSegment.TYPE_IMAGE) {
                out.append(italic(imageLabel));
            } else if (segment instanceof QaSegment) {
                appendQa((QaSegment) segment, out);
            }
        }

        // A file that doesn't end in a newline annoys every tool that reads it.
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        return out.toString();
    }

    private static void appendQa(QaSegment segment, StringBuilder out) {
        out.append("**Q:** ").append(MarkdownSerializer.toMarkdown(segment.question))
                .append("\n\n")
                .append("**A:** ").append(MarkdownSerializer.toMarkdown(segment.answer));
    }

    private static String italic(String text) {
        return "_" + text + "_";
    }
}
