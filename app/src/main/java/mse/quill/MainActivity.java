package mse.quill;

import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
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

import mse.quill.ui.audio.MiniPlayerView;
import mse.quill.util.WindowInsetsUtils;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    /** Latest bottom system-bar inset, re-applied whenever the bottom bar is shown or hidden. */
    private int bottomSystemInset;

    /** Whether this session has already put the notification request in front of the user. */
    private boolean notificationPermissionAsked;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // Nothing to do either way: playback already started, and the service copes with
                // having no visible notification.
            });

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
        setupNowPlayingBar();
        setupBottomNavigation();
    }

    /**
     * Puts the now-playing bar above the screens and settles who runs up behind the status bar.
     *
     * <p>While the bar is showing it is the topmost thing in the window, so it takes the status-bar
     * inset and the screen below it takes none; when it goes away the screens take it back. Without
     * the handover, one of the two ends up with a status bar's worth of empty space it didn't earn.
     */
    private void setupNowPlayingBar() {
        View root = findViewById(R.id.main);
        MiniPlayerView miniPlayer = findViewById(R.id.mini_player);
        WindowInsetsUtils.applyChromeTopInset(miniPlayer);
        miniPlayer.setVisibilityListener(visible ->
                WindowInsetsUtils.setChromeOwnsTopInset(root, visible));
    }

    /**
     * Asks for notification permission the first time a recording is played, and never otherwise.
     *
     * <p>The audio doesn't need it — the foreground service runs either way, so a locked phone
     * keeps playing. What is lost by refusing is the notification, which is also the lock-screen
     * and shade control. That makes "play" the only moment where the request means anything, so
     * it's the only moment it's made; asking at launch would be a prompt about a feature the user
     * hasn't touched yet.
     */
    /** Same request, for a view that only has a context — a segment deep inside a note is where
     *  the first play actually happens, and it has no handle on the activity. */
    public static void requestPlaybackNotificationPermission(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof MainActivity) {
                ((MainActivity) context).ensurePlaybackNotificationPermission();
                return;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
    }

    public void ensurePlaybackNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (notificationPermissionAsked) return; // One refusal is an answer.
        notificationPermissionAsked = true;
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
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