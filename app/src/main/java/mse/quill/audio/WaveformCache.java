package mse.quill.audio;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;

import mse.quill.data.AppExecutors;

/**
 * Turns a recorded clip into the bar heights a waveform is drawn from, once per file.
 *
 * <p>The bars are measured from the audio itself — the file is decoded to PCM and each bucket
 * keeps the RMS of the samples that fall in it. The live waveform drawn while recording can't be
 * reused for this: it comes from polling {@code MediaRecorder#getMaxAmplitude()}, is never stored,
 * and exists only for the seconds the recording dialog is open. Clips recorded before this feature
 * existed would have nothing at all.
 *
 * <p>Decoding is cheap for voice memos (tens of milliseconds for a minute of AAC) but it is still
 * disk and CPU work, so it happens on the shared background thread and the result is kept in
 * memory for the process's lifetime — scrolling a note past the same clip repeatedly, or opening
 * the mini player for one already on screen, decodes nothing.
 */
public final class WaveformCache {

    /** Resolution the file is reduced to. Views resample this to however many bars they can fit,
     *  so one decode serves both the full-width segment and the mini player's short strip. */
    private static final int BUCKETS = 256;

    private static final long CODEC_TIMEOUT_US = 10_000;

    public interface Callback {
        /** @param bars normalised 0..1 heights, or null if the clip could not be decoded. */
        void onWaveformReady(float[] bars);
    }

    private static final Map<String, float[]> cache = new HashMap<>();

    private WaveformCache() {}

    /** Delivers bars on the main thread — immediately if this clip has been decoded already. */
    public static void load(String filePath, Callback callback) {
        float[] cached;
        synchronized (cache) {
            cached = cache.get(filePath);
        }
        if (cached != null) {
            callback.onWaveformReady(cached);
            return;
        }
        AppExecutors.getInstance().diskIO(() -> {
            float[] bars = decode(filePath);
            if (bars != null) {
                synchronized (cache) {
                    cache.put(filePath, bars);
                }
            }
            AppExecutors.getInstance().mainThread(() -> callback.onWaveformReady(bars));
        });
    }

    /** Drops a clip's bars — called when its segment is deleted, so a file path that gets reused
     *  can never show the previous recording's shape. */
    public static void forget(String filePath) {
        synchronized (cache) {
            cache.remove(filePath);
        }
    }

    /** Resamples decoded buckets to exactly {@code count} bars, averaging where several buckets
     *  fall into one bar and repeating where they stretch. */
    public static float[] resample(float[] bars, int count) {
        if (bars == null || count <= 0) return new float[0];
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            int start = (int) ((long) i * bars.length / count);
            int end = (int) ((long) (i + 1) * bars.length / count);
            if (end <= start) end = Math.min(bars.length, start + 1);
            float sum = 0f;
            for (int j = start; j < end; j++) sum += bars[j];
            out[i] = sum / (end - start);
        }
        return out;
    }

    private static float[] decode(String filePath) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(filePath);
            int track = selectAudioTrack(extractor);
            if (track < 0) return null;

            MediaFormat format = extractor.getTrackFormat(track);
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            if (durationUs <= 0) return null;

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            double[] sumOfSquares = new double[BUCKETS];
            long[] counts = new long[BUCKETS];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                if (!inputDone) {
                    int index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (index >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(index);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int index = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (index >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    if (buffer != null && info.size > 0) {
                        // Which bar this buffer belongs to comes from its timestamp rather than a
                        // running sample count: a decoder is free to hand back buffers of any
                        // size, and one dropped or short buffer would otherwise skew every bar
                        // after it.
                        int bucket = (int) (info.presentationTimeUs * BUCKETS / durationUs);
                        bucket = Math.max(0, Math.min(BUCKETS - 1, bucket));
                        accumulate(buffer, info, sumOfSquares, counts, bucket);
                    }
                    codec.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    // Decoder has nothing left to give and nothing left to be fed.
                    if (info.size == 0) outputDone = true;
                }
            }

            return normalise(sumOfSquares, counts);
        } catch (Exception e) {
            // A clip that won't decode (truncated recording, codec refusing the file) is not worth
            // failing the note over — the view falls back to a flat placeholder.
            return null;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                    codec.release();
                } catch (IllegalStateException ignored) {
                    // Already dead; nothing to release cleanly.
                }
            }
            extractor.release();
        }
    }

    private static void accumulate(ByteBuffer buffer, MediaCodec.BufferInfo info,
                                   double[] sumOfSquares, long[] counts, int bucket) {
        buffer.position(info.offset);
        buffer.limit(info.offset + info.size);
        ShortBuffer samples = buffer.order(ByteOrder.nativeOrder()).asShortBuffer();
        while (samples.hasRemaining()) {
            double sample = samples.get() / (double) Short.MAX_VALUE;
            sumOfSquares[bucket] += sample * sample;
            counts[bucket]++;
        }
    }

    /** RMS per bucket, scaled so the loudest bar is 1. Normalising to the clip's own peak is what
     *  makes a quietly recorded voice memo readable instead of a flat line. */
    private static float[] normalise(double[] sumOfSquares, long[] counts) {
        float[] bars = new float[BUCKETS];
        float peak = 0f;
        for (int i = 0; i < BUCKETS; i++) {
            if (counts[i] == 0) continue;
            bars[i] = (float) Math.sqrt(sumOfSquares[i] / counts[i]);
            peak = Math.max(peak, bars[i]);
        }
        if (peak <= 0f) return null;
        for (int i = 0; i < BUCKETS; i++) bars[i] /= peak;
        return bars;
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                extractor.selectTrack(i);
                return i;
            }
        }
        return -1;
    }
}
