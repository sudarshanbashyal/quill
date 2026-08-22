package mse.quill.ui.welcome;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.PluralsRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;

import mse.quill.MainActivity;
import mse.quill.R;
import mse.quill.data.AppExecutors;
import mse.quill.onboarding.Onboarding;
import android.text.InputFilter;
import com.google.android.material.textfield.TextInputLayout;
import mse.quill.ui.profile.DisplayName;
import mse.quill.util.TextFieldUtils;
import mse.quill.onboarding.SampleData;
import mse.quill.ui.profile.ProfilePreferences;

/**
 * The first screen of a brand-new Quill: what the app is, and a choice between starting with
 * something in it or starting empty.
 *
 * <p><b>Why it exists.</b> Quill opens on an empty Home with four tabs, and nothing on that screen
 * explains what a collection is for, that a Q&amp;A block becomes a flashcard, or that the same
 * block makes a quiz. Every one of those is discoverable only by trying it. One screen naming the
 * four features, and an offer to fill the app with a worked example of all of them, replaces a
 * tour that would otherwise have to be built into every screen.
 *
 * <p><b>Why the sample is real content and not a demo mode.</b> What gets written is ordinary
 * rows — the user can edit them, pin them, lock them into a collection or delete them, and nothing
 * anywhere else in the app knows they came from here. A demo mode would need its own state, its
 * own exit, and an answer to what happens to the notes when it ends.
 *
 * <p>An Activity rather than a destination in the nav graph: this sits <em>before</em> the app
 * proper, in the same way {@code SplashActivity} does, and it must not be somewhere the bottom bar
 * can navigate back to. See {@code StartupTasks} for how the splash decides between the two.
 */
public class WelcomeActivity extends AppCompatActivity {

    /** Guards against a second tap while the first one's writes are still going through. */
    private boolean seeding;

    private LinearLayout featureList;
    private LinearLayout summaryList;
    private View welcomePane;
    private View summaryPane;
    private View welcomeActions;
    private View summaryActions;
    private MaterialButton sampleButton;
    private MaterialButton skipButton;
    private View namePane;
    private View nameActions;
    private TextInputLayout nameField;

    private OnBackPressedCallback backAfterSeeding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Quill always renders in its own light palette, whatever the system theme is doing. Set
        // here as well as in the other two entry points, since any of them can be the first
        // Activity the process creates.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);

        View root = findViewById(R.id.welcome_root);
        // The screen is one flat field with no header to run behind the status bar, so the whole
        // thing is inset rather than any part of it being allowed under the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        featureList = findViewById(R.id.welcome_features);
        summaryList = findViewById(R.id.welcome_summary_rows);
        welcomePane = findViewById(R.id.welcome_pane);
        summaryPane = findViewById(R.id.welcome_summary_pane);
        welcomeActions = findViewById(R.id.welcome_actions);
        summaryActions = findViewById(R.id.welcome_summary_actions);
        sampleButton = findViewById(R.id.welcome_sample);
        skipButton = findViewById(R.id.welcome_skip);
        namePane = findViewById(R.id.welcome_name_pane);
        nameActions = findViewById(R.id.welcome_name_actions);

        showFeatures();

        // Both answers to the content question now lead to the name, not straight into the app.
        sampleButton.setOnClickListener(v -> addSampleContent());
        skipButton.setOnClickListener(v -> showNamePane());
        findViewById(R.id.welcome_start).setOnClickListener(v -> showNamePane());
        findViewById(R.id.welcome_name_continue).setOnClickListener(v -> saveNameAndOpenMain());
        findViewById(R.id.welcome_name_skip).setOnClickListener(v -> openMain());

        // Once the content exists, back means "get on with it" rather than "leave the app" — the
        // choice this screen was asking about has been made and acted on, and dropping the user at
        // the launcher would leave them to find their way back to a Quill they haven't seen yet.
        backAfterSeeding = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                openMain();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backAfterSeeding);
    }

    // ---------- Pane one ----------

    private void showFeatures() {
        addRow(featureList, R.drawable.ic_section_note,
                getString(R.string.welcome_feature_notes_title),
                getString(R.string.welcome_feature_notes_summary));
        addRow(featureList, R.drawable.ic_flashcard,
                getString(R.string.welcome_feature_flashcards_title),
                getString(R.string.welcome_feature_flashcards_summary));
        addRow(featureList, R.drawable.ic_menu_quiz,
                getString(R.string.welcome_feature_quizzes_title),
                getString(R.string.welcome_feature_quizzes_summary));
        addRow(featureList, R.drawable.ic_section_whiteboard,
                getString(R.string.welcome_feature_whiteboards_title),
                getString(R.string.welcome_feature_whiteboards_summary));
    }

    /**
     * Writes the sample content, then reports what was written.
     *
     * <p>The button is disabled and relabelled rather than replaced by a spinner: the work is a
     * handful of local inserts and is usually over in well under a second, and a progress
     * indicator that appears and vanishes in that time reads as a flicker.
     */
    private void addSampleContent() {
        if (seeding) return;
        seeding = true;
        sampleButton.setEnabled(false);
        sampleButton.setText(R.string.welcome_action_working);
        skipButton.setEnabled(false);

        SampleData.seed(this, summary -> {
            if (isFinishing() || isDestroyed()) return;
            showSummary(summary);
        });
    }

    // ---------- Pane two ----------

    /**
     * Swaps in the report of what was created.
     *
     * <p>Every row is built from the {@link SampleData.Summary} rather than from the content this
     * screen expects to have been written — if a step somehow wrote nothing, the row for it is
     * absent instead of claiming something that isn't there.
     */
    private void showSummary(SampleData.Summary summary) {
        summaryList.removeAllViews();

        if (summary.collections > 0) {
            addRow(summaryList, R.drawable.ic_section_collection,
                    quantity(R.plurals.welcome_summary_collections, summary.collections),
                    getString(R.string.welcome_summary_collection_where, summary.collectionName));
        }
        if (summary.notes > 0) {
            addRow(summaryList, R.drawable.ic_section_note,
                    quantity(R.plurals.welcome_summary_notes, summary.notes),
                    summary.pinnedNotes > 0
                            ? getString(R.string.welcome_summary_notes_where_pinned,
                                    summary.pinnedNoteTitle)
                            : getString(R.string.welcome_summary_notes_where));
        }
        if (summary.flashcards > 0) {
            addRow(summaryList, R.drawable.ic_flashcard,
                    quantity(R.plurals.welcome_summary_flashcards, summary.flashcards),
                    getString(R.string.welcome_summary_flashcards_where));
        }
        if (summary.quizzes > 0) {
            addRow(summaryList, R.drawable.ic_menu_quiz,
                    quantity(R.plurals.welcome_summary_quizzes, summary.quizzes),
                    getString(R.string.welcome_summary_quizzes_where));
        }
        if (summary.whiteboards > 0) {
            addRow(summaryList, R.drawable.ic_section_whiteboard,
                    quantity(R.plurals.welcome_summary_whiteboards, summary.whiteboards),
                    getString(R.string.welcome_summary_whiteboards_where));
        }

        welcomePane.setVisibility(View.GONE);
        welcomeActions.setVisibility(View.GONE);
        summaryPane.setVisibility(View.VISIBLE);
        summaryActions.setVisibility(View.VISIBLE);
        backAfterSeeding.setEnabled(true);
    }

    // ---------- Pane three ----------

    /**
     * Asks what to call the user, with a suggestion already in the box.
     *
     * <p>Pre-filled rather than empty, and that is the whole design. An empty field asks a stranger
     * to invent something before they have seen the app, and most people type nothing — which is
     * how everyone ends up unnamed and a shared whiteboard has two of nobody on it. A suggestion
     * turns the question into a choice between "fine" and "actually, call me this", and both
     * answers leave with a name.
     *
     * <p>The suggestion is written to preferences on the way in, not on the way out, so the skip
     * path has nothing to do and a process death mid-screen still leaves a named install.
     */
    private void showNamePane() {
        String suggested = ProfilePreferences.ensureDefaultName(this);

        String[] emoji = getResources().getStringArray(R.array.welcome_name_emoji);
        ((TextView) findViewById(R.id.welcome_name_emoji))
                .setText(emoji[new java.util.Random().nextInt(emoji.length)]);

        if (nameField == null) {
            nameField = TextFieldUtils.outlinedField(this, R.string.welcome_name_hint);
            // The same filter the Profile screen puts on its field, so the rule about what a name
            // may contain is enforced in one place and felt identically in both.
            nameField.getEditText().setFilters(new InputFilter[]{DisplayName.filter()});
            ((LinearLayout) findViewById(R.id.welcome_name_field_holder)).addView(nameField);
        }
        nameField.getEditText().setText(suggested);
        nameField.getEditText().setSelection(suggested.length());

        welcomePane.setVisibility(View.GONE);
        welcomeActions.setVisibility(View.GONE);
        summaryPane.setVisibility(View.GONE);
        summaryActions.setVisibility(View.GONE);
        namePane.setVisibility(View.VISIBLE);
        nameActions.setVisibility(View.VISIBLE);
        // Back from here means "get on with it": the content question has already been answered and
        // acted on, and there is a name stored either way.
        backAfterSeeding.setEnabled(true);
    }

    /** Keeps whatever is in the field, falling back to the suggestion if it was emptied — leaving
     *  with no name at all is the one outcome this screen exists to prevent. */
    private void saveNameAndOpenMain() {
        String typed = nameField.getEditText().getText().toString();
        if (!DisplayName.sanitize(typed).isEmpty()) {
            ProfilePreferences.setDisplayName(this, typed);
        }
        openMain();
    }

    // ---------- Leaving ----------

    /**
     * Records that the welcome screen has been answered and opens the app.
     *
     * <p>The flag is written on the disk thread — it is a blocking {@code commit()} (see
     * {@link Onboarding#markWelcomeSeen}) — but the hand-off does not wait for it. Making the user
     * watch a preference write would be absurd, and the worst case if the process dies in that
     * window is that this screen is offered once more over an app that already has the sample
     * content in it, where "start empty" still does the right thing.
     */
    private void openMain() {
        // A belt-and-braces call: showNamePane has already stored a name by the time either of its
        // buttons can be pressed. This covers the back gesture, which reaches here without either.
        ProfilePreferences.ensureDefaultName(this);
        AppExecutors.getInstance().diskIO(() -> Onboarding.markWelcomeSeen(getApplicationContext()));
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    // ---------- Rows ----------

    private void addRow(@NonNull LinearLayout into, @DrawableRes int icon,
                        CharSequence title, CharSequence summary) {
        View row = LayoutInflater.from(this).inflate(R.layout.view_welcome_row, into, false);
        ((ImageView) row.findViewById(R.id.welcome_row_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.welcome_row_title)).setText(title);
        ((TextView) row.findViewById(R.id.welcome_row_summary)).setText(summary);
        into.addView(row);
    }

    private String quantity(@PluralsRes int plural, int count) {
        return getResources().getQuantityString(plural, count, count);
    }
}
