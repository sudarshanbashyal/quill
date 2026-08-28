package mse.quill.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mse.quill.data.model.AudioSegment;
import mse.quill.data.model.HeadingMarker;
import mse.quill.data.model.NoteSegment;
import mse.quill.data.model.QaSegment;
import mse.quill.data.model.TextSegment;

/**
 * A note as read-aloud hears it: its words and its recordings, in the order they appear.
 *
 * <p>Read-aloud used to be handed one string, which meant a note's voice memos were silently
 * skipped — the one part of a note that is already someone talking was the part listening to it
 * left out. A note is a document, so the recording embedded halfway down belongs halfway down:
 * the text before it is spoken, then the recording plays, then the text after it carries on.
 *
 * <p>Consecutive text — separate segments on screen, and both halves of a Q&amp;A — is merged into
 * a single item, so a recording is the only thing that breaks the reading into pieces. Everything
 * else in a note (images, whiteboards) has nothing to say and doesn't appear here at all.
 *
 * <p>A playlist is a snapshot. It is built when the reading starts and doesn't track later edits,
 * which is what lets a reading outlive the screen it was started from.
 */
public final class ReadPlaylist {

    /**
     * How fast the voice is assumed to get through text, for weighing a text item against a
     * recording whose real length is known.
     *
     * <p>Only ever used to decide how much of the progress bar each item is worth — never shown as
     * a time. A typical engine reads at roughly 150 words a minute, which is about this many
     * characters a second.
     */
    private static final float CHARS_PER_SECOND = 15f;

    /** One thing to be played: a run of text for the voice, or a recording of the user's own. */
    public static final class Item {

        /** The words to speak, or null if this item is a recording. */
        public final String text;
        /** The recording to play, or null if this item is text. */
        public final String filePath;
        /** The recording's length; meaningless for text. */
        public final int durationMs;

        private Item(String text, String filePath, int durationMs) {
            this.text = text;
            this.filePath = filePath;
            this.durationMs = durationMs;
        }

        public boolean isClip() {
            return filePath != null;
        }

        /**
         * Roughly how long this item will take, in milliseconds.
         *
         * <p>The progress bar spans the whole reading, so the items have to be comparable to each
         * other somehow. Counting them equally would make a two-minute recording worth exactly as
         * much as the word before it, and the bar would jump.
         */
        float weightMs() {
            if (isClip()) return Math.max(1, durationMs);
            return Math.max(1, text.length()) * 1000f / CHARS_PER_SECOND;
        }
    }

    private final List<Item> items;

    private ReadPlaylist(List<Item> items) {
        this.items = Collections.unmodifiableList(items);
    }

    public List<Item> items() {
        return items;
    }

    /** Nothing to say and nothing to play — the case where offering to read a note is a lie. */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builds a playlist from a note that has been loaded rather than opened — the watch asking for
     * a note to be read is the case, where there are no editor views to walk.
     */
    public static ReadPlaylist fromSegments(List<NoteSegment> segments) {
        Builder builder = builder();
        if (segments == null) return builder.build();
        for (NoteSegment segment : segments) {
            if (segment instanceof TextSegment) {
                builder.addText(((TextSegment) segment).content);
            } else if (segment instanceof QaSegment) {
                QaSegment qa = (QaSegment) segment;
                builder.addText(qa.question);
                builder.addText(qa.answer);
            } else if (segment instanceof AudioSegment) {
                AudioSegment audio = (AudioSegment) segment;
                builder.addClip(audio.filePath, audio.durationMs);
            }
        }
        return builder.build();
    }

    /** Assembles a playlist from a note's parts in reading order. */
    public static final class Builder {

        private final List<Item> items = new ArrayList<>();
        private final StringBuilder pendingText = new StringBuilder();

        private Builder() {}

        /**
         * Adds words to the run currently being assembled.
         *
         * <p>Segments are separated with a full stop rather than a newline: the voice pauses at
         * one, and two blocks of prose run together without it. Heading markers are stripped —
         * they are invisible on screen, so they must not reach the engine.
         */
        public Builder addText(CharSequence text) {
            if (text == null || text.length() == 0) return this;
            if (pendingText.length() > 0) pendingText.append(". ");
            String[] lines = text.toString().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) pendingText.append('\n');
                pendingText.append(HeadingMarker.strip(lines[i]));
            }
            return this;
        }

        /** Adds a recording, which ends whatever text run was being assembled before it. */
        public Builder addClip(String filePath, int durationMs) {
            if (filePath == null) return this;
            flushText();
            items.add(new Item(null, filePath, durationMs));
            return this;
        }

        public ReadPlaylist build() {
            flushText();
            return new ReadPlaylist(new ArrayList<>(items));
        }

        /** Whitespace-only runs — a note of blank lines — are dropped rather than becoming an
         *  item the engine would spend no time on and report as failed. */
        private void flushText() {
            String text = pendingText.toString().trim();
            pendingText.setLength(0);
            if (!text.isEmpty()) items.add(new Item(text, null, 0));
        }
    }
}
