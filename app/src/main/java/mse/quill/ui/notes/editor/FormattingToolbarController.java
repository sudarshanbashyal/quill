package mse.quill.ui.notes.editor;

import android.widget.LinearLayout;

import mse.quill.R;

public class FormattingToolbarController {

    public interface FormatListener {
        void onBoldToggled();
        void onItalicToggled();
        void onUnderlineToggled();
        void onHeading1Toggled();
        void onHeading2Toggled();
        void onBulletListToggled();
        void onImageRequested();
        void onAudioRequested();
        void onQaBlockRequested();
        void onWhiteboardRequested();
    }

    private final FormattingButtonView boldButton;
    private final FormattingButtonView italicButton;
    private final FormattingButtonView underlineButton;
    private final FormattingButtonView heading1Button;
    private final FormattingButtonView heading2Button;
    private final FormattingButtonView bulletButton;
    private final FormattingButtonView imageButton;
    private final FormattingButtonView micButton;
    private final FormattingButtonView qaButton;
    private final FormattingButtonView whiteboardButton;

    public FormattingToolbarController(LinearLayout container, FormatListener listener) {
        boldButton = addButton(container, R.drawable.ic_bold,
                R.string.action_bold, listener::onBoldToggled);
        italicButton = addButton(container, R.drawable.ic_italic,
                R.string.action_italic, listener::onItalicToggled);
        underlineButton = addButton(container, R.drawable.ic_underline,
                R.string.action_underline, listener::onUnderlineToggled);
        heading1Button = addButton(container, R.drawable.ic_h1,
                R.string.action_heading_1, listener::onHeading1Toggled);
        heading2Button = addButton(container, R.drawable.ic_h2,
                R.string.action_heading_2, listener::onHeading2Toggled);
        bulletButton = addButton(container, R.drawable.ic_list,
                R.string.action_bullet_list, listener::onBulletListToggled);
        imageButton = addButton(container, R.drawable.ic_image,
                R.string.action_insert_image, listener::onImageRequested);
        micButton = addButton(container, R.drawable.ic_mic,
                R.string.action_record_audio, listener::onAudioRequested);
        qaButton = addButton(container, R.drawable.ic_question,
                R.string.action_qa_block, listener::onQaBlockRequested);
        whiteboardButton = addButton(container, R.drawable.ic_whiteboard,
                R.string.action_attach_whiteboard, listener::onWhiteboardRequested);
    }

    /**
     * Availability is applied before active state, and {@link FormattingButtonView#setAvailable}
     * clears the marker when a control goes away — so a heading marker can't be left lit after the
     * caret moves into a Q&amp;A field where headings don't exist.
     */
    public void updateState(FormattingState state) {
        heading1Button.setAvailable(state.headingsAllowed);
        heading2Button.setAvailable(state.headingsAllowed);
        imageButton.setAvailable(state.embedsAllowed);
        micButton.setAvailable(state.embedsAllowed);
        // A Q&A block is itself a block, so it's offered exactly where other blocks are — which
        // also stops one being nested inside another.
        qaButton.setAvailable(state.embedsAllowed);
        whiteboardButton.setAvailable(state.embedsAllowed);

        boldButton.setActive(state.bold);
        italicButton.setActive(state.italic);
        underlineButton.setActive(state.underline);
        bulletButton.setActive(state.bullet);
        if (state.headingsAllowed) {
            heading1Button.setActive(state.headingLevel == 1);
            heading2Button.setActive(state.headingLevel == 2);
        }
    }

    /** Reflects whether a recording is currently in progress on the mic button itself, since it
     *  is also the control used to stop the recording. */
    public void setRecordingState(boolean recording) {
        micButton.setIcon(recording ? R.drawable.ic_pause : R.drawable.ic_mic);
        micButton.setContentDescription(micButton.getContext().getString(
                recording ? R.string.action_stop_recording : R.string.action_record_audio));
        micButton.setActive(recording);
    }

    private FormattingButtonView addButton(LinearLayout container, int iconRes,
                                           int contentDescriptionRes, Runnable action) {
        FormattingButtonView button = new FormattingButtonView(
                container.getContext(), iconRes, container.getContext().getString(contentDescriptionRes));
        button.setOnClickListener(v -> action.run());
        // Equal weights, so the row divides the bar's full width however many items it holds.
        container.addView(button, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        return button;
    }
}
