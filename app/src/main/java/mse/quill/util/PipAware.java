package mse.quill.util;

/**
 * Implemented by any fragment that has something useful to do while its Activity is in Android's
 * Picture-in-Picture mode — currently only {@link mse.quill.ui.whiteboard.WhiteboardFragment},
 * which hides its toolbar so the floating window shows nothing but the drawing.
 */
public interface PipAware {
    /** {@code true} the moment the window has shrunk into PIP, {@code false} the moment it's back
     *  to full size — mirrors {@code Activity#onPictureInPictureModeChanged}. */
    void onPipModeChanged(boolean isInPictureInPictureMode);
}
