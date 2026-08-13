package mse.quill.util;

import android.content.Context;
import android.text.InputType;
import android.widget.LinearLayout;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import mse.quill.R;

/**
 * Builds Material 3 outlined text fields in code, for the dialogs that assemble their views
 * programmatically instead of inflating XML (see NoteRowView for why this codebase avoids the
 * inflater).
 *
 * The outlined variant has to be requested via an explicit defStyleAttr — TextInputLayout's
 * plain constructor yields the *filled* style, whose underline reads as Material 2.
 *
 * Note the child is constructed with {@code layout.getContext()}, not the caller's context:
 * TextInputLayout wraps its own themed context, and a TextInputEditText built from the outer
 * context silently misses the box styling.
 */
public final class TextFieldUtils {

    private TextFieldUtils() {}

    /**
     * An outlined single-line text field. Read and write its contents through
     * {@code field.getEditText()}.
     */
    public static TextInputLayout outlinedField(Context context, int hintRes) {
        TextInputLayout layout = new TextInputLayout(
                context, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        layout.setHint(context.getString(hintRes));

        TextInputEditText editText = new TextInputEditText(layout.getContext());
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        // Must be LinearLayout.LayoutParams: TextInputLayout is itself a LinearLayout and casts
        // its child's params in addView(), so plain ViewGroup.LayoutParams crashes at runtime.
        editText.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(editText);

        layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return layout;
    }

    /**
     * Wraps a field in the padding a dialog doesn't supply: {@code setView()} places content flush
     * against the dialog's edges, which puts the outlined box hard against the title above it.
     *
     * <p>Lives here rather than beside the first dialog that needed it (it started out inside
     * {@code CollectionDialogs}) because every {@code setView}-with-a-text-field dialog wants the
     * same treatment, and the Profile screen's are in another package.
     */
    public static LinearLayout inset(Context context, TextInputLayout field) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int horizontal = context.getResources().getDimensionPixelSize(R.dimen.spacing_lg);
        int top = context.getResources().getDimensionPixelSize(R.dimen.spacing_sm);
        container.setPadding(horizontal, top, horizontal, 0);
        container.addView(field);
        return container;
    }
}
