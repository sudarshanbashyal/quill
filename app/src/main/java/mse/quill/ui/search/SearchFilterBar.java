package mse.quill.ui.search;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

import mse.quill.R;
import mse.quill.data.model.Tag;

/**
 * The search box, its filter button, and the row of what is currently filtering — one control,
 * because Home and a collection's detail screen both need exactly this and had drifted into two
 * copies of the field alone.
 *
 * <p>It holds no filter state. The screen owns a {@link NoteFilter} and hands it back in
 * {@link #render}; the bar only reports taps and typing. That keeps the "what is being asked for"
 * in one place per screen rather than split between a view and its host.
 */
public class SearchFilterBar extends LinearLayout {

    public interface Listener {
        void onQueryChanged(String query);
        /** The filter button was tapped — the screen opens {@link SearchFilterDialog}. */
        void onFilterRequested();
        /** A chip in the active-filter row was tapped, meaning "drop this one". */
        void onFilterCleared();
    }

    private final TextInputEditText searchInput;
    private final LinearLayout activeFilters;
    private final View activeFiltersScroll;

    private Listener listener;

    public SearchFilterBar(Context context) {
        this(context, null);
    }

    public SearchFilterBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_search_filter_bar, this, true);

        searchInput = findViewById(R.id.search_input);
        activeFilters = findViewById(R.id.active_filters_container);
        activeFiltersScroll = findViewById(R.id.active_filters_scroll);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (listener != null) listener.onQueryChanged(s.toString());
            }
        });

        MaterialButton filterButton = findViewById(R.id.filter_button);
        filterButton.setOnClickListener(v -> {
            if (listener != null) listener.onFilterRequested();
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setHint(int hintRes) {
        searchInput.setHint(hintRes);
    }

    /**
     * Redraws the active-filter row from {@code filter}.
     *
     * @param allTags every tag in the app, so a selected id can be resolved to the name and colour
     *                it is drawn in elsewhere. A selected tag that no longer exists is skipped
     *                rather than drawn as a blank chip.
     */
    public void render(NoteFilter filter, List<Tag> allTags) {
        activeFilters.removeAllViews();

        List<Tag> selected = new ArrayList<>();
        for (Tag tag : allTags) {
            if (filter.hasTag(tag.id)) selected.add(tag);
        }

        for (Tag tag : selected) {
            View chip = FilterChips.removableTag(getContext(), tag, () -> {
                filter.removeTag(tag.id);
                if (listener != null) listener.onFilterCleared();
            });
            activeFilters.addView(chip);
        }

        // The sort is shown only when it isn't the default, and it clears back to the default
        // rather than disappearing — there is always *some* ordering.
        if (filter.sort() != NoteFilter.Sort.RECENT) {
            activeFilters.addView(FilterChips.removable(getContext(),
                    getContext().getString(filter.sort().labelRes), () -> {
                        filter.setSort(NoteFilter.Sort.RECENT);
                        if (listener != null) listener.onFilterCleared();
                    }));
        }

        activeFiltersScroll.setVisibility(activeFilters.getChildCount() == 0 ? GONE : VISIBLE);
    }
}
