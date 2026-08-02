package mse.quill;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import mse.quill.util.WindowInsetsUtils;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    /** Latest bottom system-bar inset, re-applied whenever the bottom bar is shown or hidden. */
    private int bottomSystemInset;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Quill always renders in its own light palette, independent of the system theme.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // Only the side insets are the activity's business. The bottom one belongs to whatever
            // reaches the bottom of the screen (see applyBottomInset()), and the top one is left to
            // each screen via WindowInsetsUtils.applyTopInset — padding it here would put a strip
            // of window background behind the status bar on every screen, instead of letting the
            // screen's own header or background run up behind it.
            v.setPadding(systemBars.left, 0, systemBars.right, 0);
            bottomSystemInset = systemBars.bottom;
            applyBottomInset();
            return insets;
        });

        applyTopInsetToEveryScreen();
        setupBottomNavigation();
    }

    /**
     * Gives every screen the status-bar inset as its view is created, so the bar shows the screen
     * rather than a strip of window background — and so no fragment has to remember to ask.
     *
     * <p>A screen takes the inset on its own root unless it implements
     * {@link WindowInsetsUtils.TopInsetHost} and names a different view. {@code recursive = true}
     * because the screens live in the nav host's *child* fragment manager, not this one; the nav
     * host itself is skipped, since padding the container is exactly the white band this avoids,
     * and dialogs are skipped because they are their own windows.
     */
    private void applyTopInsetToEveryScreen() {
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(@NonNull FragmentManager fm,
                                                      @NonNull Fragment fragment,
                                                      @NonNull View view,
                                                      @Nullable Bundle savedInstanceState) {
                        if (fragment instanceof NavHostFragment
                                || fragment instanceof DialogFragment) {
                            return;
                        }
                        View target = fragment instanceof WindowInsetsUtils.TopInsetHost
                                ? ((WindowInsetsUtils.TopInsetHost) fragment).topInsetTarget(view)
                                : view;
                        WindowInsetsUtils.applyTopInset(target);
                    }
                }, true);
    }

    /**
     * Hands the gesture-bar inset to whichever view actually reaches the bottom of the screen.
     *
     * <p>{@link BottomNavigationView} already pads itself by that inset, so the root padding this
     * replaces was charging for it twice: the bar drew its own inset-height strip and then sat on
     * a second, empty one, costing the content above ~24dp for nothing. When the bar is hidden —
     * the editor, a review session — the nav host is what reaches the bottom, so it takes the
     * inset instead and content still clears the gesture pill.
     */
    private void applyBottomInset() {
        View navHost = findViewById(R.id.nav_host_fragment);
        View bottomNav = findViewById(R.id.bottom_nav);
        boolean barVisible = bottomNav.getVisibility() == View.VISIBLE;
        navHost.setPadding(0, 0, 0, barVisible ? 0 : bottomSystemInset);

        // The bar's height is set here rather than in the layout because it has to be the row of
        // items *plus* the inset the bar pads itself by — at wrap_content M3 floors it at 80dp
        // before that padding, which is taller than this app needs and comes straight off the
        // list above it. app:itemPaddingTop/Bottom alone don't move it; the floor wins.
        ViewGroup.LayoutParams params = bottomNav.getLayoutParams();
        int height = getResources().getDimensionPixelSize(R.dimen.bottom_nav_height)
                + bottomSystemInset;
        if (params.height != height) {
            params.height = height;
            bottomNav.setLayoutParams(params);
        }
    }

    /**
     * Wires the top-level destinations to the bottom bar.
     *
     * <p>The menu's item ids <em>are</em> the destination ids, which is what lets {@link
     * NavigationUI} handle selection and the back stack — switching tabs pops back to the start
     * destination rather than stacking Home on Flashcards on Home. The bar then hides itself
     * anywhere deeper: a note editor or a review session is somewhere you arrived from a tab, and
     * offering to jump away mid-note is noise.
     */
    private void setupBottomNavigation() {
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (host == null) return;

        NavController navController = host.getNavController();
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        NavigationUI.setupWithNavController(bottomNav, navController);

        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            boolean topLevel = destination.getId() == R.id.homeFragment
                    || destination.getId() == R.id.flashcardDecksFragment
                    || destination.getId() == R.id.quizzesFragment;
            bottomNav.setVisibility(topLevel ? View.VISIBLE : View.GONE);
            applyBottomInset();
        });
    }
}