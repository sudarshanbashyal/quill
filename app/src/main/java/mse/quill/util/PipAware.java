package mse.quill.util;

/**
 * The two halves of Picture-in-Picture, one per direction.
 *
 * <p>{@link PipAware} is what the Activity calls on the fragment; {@link PipHost} is what the
 * fragment calls back. Until 2026-08-28 only the first existed, and the return trip was a cast to
 * the concrete {@code MainActivity} — so the screen could be swapped for another Activity in one
 * direction but not the other, which is the kind of asymmetry that goes unnoticed until something
 * tries.
 */
public interface PipAware {

    /** {@code true} the moment the window has shrunk into PIP, {@code false} the moment it's back
     *  to full size — mirrors {@code Activity#onPictureInPictureModeChanged}. */
    void onPipModeChanged(boolean isInPictureInPictureMode);

    /**
     * Implemented by the Activity hosting a {@link PipAware} screen. Currently {@code MainActivity},
     * which is also the only Activity Quill has.
     */
    interface PipHost {
        /**
         * Shrinks the window into PIP, shaped like whatever the caller is showing.
         *
         * @param aspectWidth  the content's width; only its ratio to {@code aspectHeight} matters
         * @param aspectHeight the content's height
         */
        void enterWhiteboardPip(float aspectWidth, float aspectHeight);
    }
}
