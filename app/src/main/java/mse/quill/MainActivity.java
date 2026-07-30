package mse.quill;

import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Quill always renders in its own light palette, independent of the system theme.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        setupBottomNavigation();
    }

    /**
     * Wires the two top-level destinations to the bottom bar.
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
                    || destination.getId() == R.id.flashcardDecksFragment;
            bottomNav.setVisibility(topLevel ? View.VISIBLE : View.GONE);
        });
    }
}