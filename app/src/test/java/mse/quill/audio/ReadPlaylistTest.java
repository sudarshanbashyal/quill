package mse.quill.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import mse.quill.data.model.HeadingMarker;

/** What a note sounds like: which parts of it are handed to the voice, and where its recordings
 *  land among them. */
public class ReadPlaylistTest {

    @Test
    public void mergesConsecutiveTextIntoOneItem() {
        List<ReadPlaylist.Item> items = ReadPlaylist.builder()
                .addText("First block")
                .addText("Second block")
                .build()
                .items();

        assertEquals(1, items.size());
        // Full-stopped rather than newline-joined: the voice pauses at one, and two blocks of
        // prose run together without it.
        assertEquals("First block. Second block", items.get(0).text);
        assertFalse(items.get(0).isClip());
    }

    @Test
    public void recordingSplitsTheTextAroundIt() {
        List<ReadPlaylist.Item> items = ReadPlaylist.builder()
                .addText("Before")
                .addClip("/notes/memo.m4a", 5_000)
                .addText("After")
                .build()
                .items();

        assertEquals(3, items.size());
        assertEquals("Before", items.get(0).text);

        ReadPlaylist.Item clip = items.get(1);
        assertTrue(clip.isClip());
        assertEquals("/notes/memo.m4a", clip.filePath);
        assertEquals(5_000, clip.durationMs);
        assertNull(clip.text);

        assertEquals("After", items.get(2).text);
    }

    /** The marker is invisible on screen, so the engine must never be handed it. */
    @Test
    public void stripsHeadingMarkersLineByLine() {
        List<ReadPlaylist.Item> items = ReadPlaylist.builder()
                .addText(HeadingMarker.forLevel(HeadingMarker.H1) + "Title\n"
                        + HeadingMarker.forLevel(HeadingMarker.H2) + "Subtitle")
                .build()
                .items();

        assertEquals("Title\nSubtitle", items.get(0).text);
    }

    /** A note that is nothing but a voice memo is still worth reading — that case is the whole
     *  point of recordings being in the playlist at all. */
    @Test
    public void aNoteOfOnlyRecordingsIsNotEmpty() {
        ReadPlaylist playlist = ReadPlaylist.builder()
                .addText("   \n  ")
                .addClip("/notes/memo.m4a", 1_000)
                .build();

        assertFalse(playlist.isEmpty());
        assertEquals(1, playlist.items().size());
        assertTrue(playlist.items().get(0).isClip());
    }

    @Test
    public void blankTextAloneMakesAnEmptyPlaylist() {
        assertTrue(ReadPlaylist.builder().addText("").addText("  \n ").build().isEmpty());
        assertTrue(ReadPlaylist.builder().build().isEmpty());
    }

    /** A long recording has to be worth more of the progress bar than the word next to it. */
    @Test
    public void weighsARecordingByItsLengthAndTextByItsSize() {
        ReadPlaylist.Item clip = ReadPlaylist.builder()
                .addClip("/notes/memo.m4a", 60_000)
                .build().items().get(0);
        ReadPlaylist.Item shortText = ReadPlaylist.builder()
                .addText("Hello")
                .build().items().get(0);

        assertEquals(60_000f, clip.weightMs(), 0.001f);
        assertTrue(shortText.weightMs() < clip.weightMs());
        assertTrue(shortText.weightMs() > 0f);
    }
}
