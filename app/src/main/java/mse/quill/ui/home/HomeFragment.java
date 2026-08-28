package mse.quill.ui.home;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import mse.quill.R;
import com.google.android.material.snackbar.Snackbar;

import mse.quill.data.AppExecutors;
import mse.quill.data.CollectionRepository;
import mse.quill.data.NoteImporter;
import mse.quill.data.NoteStore;
import mse.quill.data.Repositories;
import mse.quill.data.WhiteboardRepository;
import mse.quill.data.model.Collection;
import mse.quill.data.TagRepository;
import mse.quill.data.model.Note;
import mse.quill.data.model.Tag;
import mse.quill.data.model.Whiteboard;
import mse.quill.ui.collections.CollectionDetailFragment;
import mse.quill.ui.collections.CollectionLockFlow;
import mse.quill.ui.notes.NoteEditorFragment;
import mse.quill.ui.profile.ProfilePreferences;
import mse.quill.ui.search.NoteFilter;
import mse.quill.ui.search.SearchFilterBar;
import mse.quill.ui.search.SearchFilterDialog;
import mse.quill.ui.whiteboard.WhiteboardFragment;
import mse.quill.collab.CollabEntry;
import mse.quill.collab.CollabSessionHolder;
import mse.quill.util.ColorUtils;
import mse.quill.util.NoteDisplayUtils;
import java.util.Random;
import mse.quill.ui.common.SwipeToDelete;
import mse.quill.ui.common.UndoDelete;
import mse.quill.ui.common.WindowInsetsUtils;

public class HomeFragment extends Fragment implements WindowInsetsUtils.TopInsetHost {

    /**
     * Nothing — Home's {@code AppBarLayout} takes the inset itself via {@code fitsSystemWindows}.
     *
     * <p>It has to. The greeting scrolls away and the search bar stays, so the inset belongs to the
     * bar rather than to any one child; and padding the AppBarLayout by hand doesn't work either,
     * because it offsets its scrolling child <em>within</em> its own bounds and the pinned cards
     * then draw up through the padding into the status bar. AppBarLayout's own inset handling keeps
     * children below the strip and still paints its background (the gradient) behind it.
     */
    @Override
    public View topInsetTarget(View root) {
        return null;
    }

    private NoteStore noteRepository ;
    private CollectionRepository collectionRepository;
    private WhiteboardRepository whiteboardRepository;

    private HomeAdapter homeAdapter;
    private View pinnedSection;
    private LinearLayout pinnedCardsContainer;

    /** How many pinned cards to reserve room for before the read comes back — see
     *  {@link #showPinnedPlaceholders()}. */
    private static final String HOME_PREFS = "home_prefs";
    private static final String KEY_PINNED_COUNT = "pinned_count";

    private List<Collection> allCollections = new ArrayList<>();
    private List<Whiteboard> allWhiteboards = new ArrayList<>();
    private List<Note> allNotes = new ArrayList<>();
    private List<Tag> allTags = new ArrayList<>();

    /** Which header is on screen, and which of its lines were drawn for this visit. Held so that
     *  coming back from a note doesn't reshuffle the greeting under the user — see renderHeader. */
    private static final float SUBTITLE_ALPHA = 0.75f;
    private static final Random RANDOM = new Random();

    private TimeOfDay headerPeriod;
    private int greetingIndex;
    private int subtitleIndex;
    /** Survives a reload; the list is re-derived from it rather than the other way round. */
    private final NoteFilter filter = new NoteFilter();
    private SearchFilterBar searchBar;
    private TagRepository tagRepository;

    private NoteImporter noteImporter;
    private mse.quill.data.WhiteboardImporter whiteboardImporter;
    private mse.quill.data.CollectionImporter collectionImporter;
    private ActivityResultLauncher<String[]> importPicker;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Registered here rather than in onViewCreated: a launcher has to exist before the fragment
        // reaches STARTED, or the result of a picker that outlived a process death has nowhere to
        // be delivered.
        importPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) importBundle(uri); });
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                              Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        noteRepository = Repositories.notes(requireContext());
        collectionRepository = new CollectionRepository(requireContext());
        tagRepository = new TagRepository(requireContext());
        whiteboardRepository = new WhiteboardRepository(requireContext());
        noteImporter = new NoteImporter(requireContext());
        whiteboardImporter = new mse.quill.data.WhiteboardImporter(requireContext());
        collectionImporter = new mse.quill.data.CollectionImporter(requireContext());

        homeAdapter = new HomeAdapter(new HomeAdapter.Listener() {
            @Override public void onCreateCollectionRequested() { createCollection(); }

            @Override public void onCreateNoteRequested() { createNote(); }

            @Override public void onCreateWhiteboardRequested() { createWhiteboard(); }

            @Override public void onCollectionClicked(String collectionId, String displayName) {
                // Gated here rather than on the collection screen itself: opening the screen and
                // then covering it means the destination has already queried its notes, and a
                // dismissed prompt would leave the user looking at an empty version of a
                // collection that isn't empty.
                CollectionLockFlow.openCollection(requireActivity(), collectionId, displayName,
                        isCollectionLocked(collectionId),
                        () -> openCollection(collectionId, displayName));
            }

            @Override public void onCollectionLongPressed(Collection collection) {
                showManageCollectionDialog(collection);
            }

            @Override public void onNoteClicked(Note note) { openNote(note.id); }

            @Override public void onNoteLongPressed(Note note) {
                boolean isPinned = note.pinnedAt != null;
                CollectionDialogs.showNoteActionsDialog(requireContext(), allCollections, note.collectionId, isPinned,
                        collectionId -> noteRepository.assignCollection(note.id, collectionId, HomeFragment.this::reloadAll),
                        () -> togglePin(note, isPinned),
                        () -> deleteNoteWithUndo(note));
            }

            @Override public void onWhiteboardClicked(Whiteboard whiteboard) {
                openWhiteboard(whiteboard.id, whiteboard.noteId);
            }

            @Override public void onWhiteboardLongPressed(Whiteboard whiteboard) {
                showManageWhiteboardDialog(whiteboard);
            }
        });

        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), 2);
        layoutManager.setSpanSizeLookup(homeAdapter.spanSizeLookup());

        RecyclerView recyclerView = view.findViewById(R.id.recycler_home);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(homeAdapter);
        // Note rows only. The section headers and the two card grids share this list, and a card
        // sliding out from under a full-width red panel would be promising a gesture its layout
        // doesn't have — they are also what leaves room for the swipe-between-tabs gesture, which
        // is deliberately the loser wherever there is a row to delete.
        SwipeToDelete.attach(recyclerView, new SwipeToDelete.Target() {
            @Override public boolean isSwipeable(RecyclerView.ViewHolder holder) {
                return homeAdapter.noteAt(holder.getBindingAdapterPosition()) != null;
            }

            @Override public void onSwiped(RecyclerView.ViewHolder holder) {
                Note note = homeAdapter.noteAt(holder.getBindingAdapterPosition());
                if (note != null) deleteNoteWithUndo(note);
            }
        });

        applyStatusBarScrim(view);
        // Before the first draw, so Home never appears in the wrong sky for a frame and then
        // corrects itself. A fresh view is also a fresh visit, which is when new lines are drawn.
        headerPeriod = null;
        renderHeader();

        pinnedSection = view.findViewById(R.id.pinned_section);
        pinnedCardsContainer = view.findViewById(R.id.pinned_cards_container);
        // Before the first read, so the band is already the right height when the page draws.
        showPinnedPlaceholders();

        searchBar = view.findViewById(R.id.search_bar);
        searchBar.setListener(new SearchFilterBar.Listener() {
            @Override public void onQueryChanged(String query) {
                filter.setQuery(query);
                // Drawn twice on purpose: once now from what is already in memory, so the list
                // responds to the keystroke, and again when the index answers with the notes whose
                // *body* matches. Waiting for the round trip would make typing feel laggy for the
                // sake of results that mostly agree.
                applyFilters();
                noteRepository.searchNoteIds(query, ids -> {
                    if (!isAdded()) return;
                    filter.setFullTextMatches(query, ids);
                    applyFilters();
                });
            }

            @Override public void onFilterRequested() {
                SearchFilterDialog.show(requireContext(), filter, allTags, HomeFragment.this::onFilterChanged);
            }

            @Override public void onFilterCleared() {
                onFilterChanged();
            }
        });

        setupFabMenu(view);
    }

    /** Sizes the scrim to the status bar — see the note on it in fragment_home.xml. */
    private void applyStatusBarScrim(View root) {
        View scrim = root.findViewById(R.id.status_bar_scrim);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scrim, (v, insets) -> {
            int top = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()).top;
            if (v.getLayoutParams().height != top) {
                v.getLayoutParams().height = top;
                v.requestLayout();
            }
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(scrim);
    }

    private static final long FAB_OPTIONS_ANIM_DURATION_MS = 180;

    private void setupFabMenu(View view) {
        View fabOptions = view.findViewById(R.id.fab_options);
        View fabOptionNote = view.findViewById(R.id.fab_option_note);
        View fabOptionCollection = view.findViewById(R.id.fab_option_collection);
        View fabOptionWhiteboard = view.findViewById(R.id.fab_option_whiteboard);
        int sweepDistance = getResources().getDimensionPixelSize(R.dimen.fab_option_sweep_distance);

        view.findViewById(R.id.fab_new_note).setOnClickListener(v -> {
            boolean expanded = fabOptions.getVisibility() == View.VISIBLE;
            if (expanded) {
                collapseFabOptions(fabOptions, sweepDistance);
            } else {
                expandFabOptions(fabOptions, sweepDistance);
            }
        });

        fabOptionNote.setOnClickListener(v -> {
            collapseFabOptions(fabOptions, sweepDistance);
            createNote();
        });

        fabOptionCollection.setOnClickListener(v -> {
            collapseFabOptions(fabOptions, sweepDistance);
            createCollection();
        });

        View fabOptionImport = view.findViewById(R.id.fab_option_import);
        fabOptionImport.setOnClickListener(v -> {
            collapseFabOptions(fabOptions, sweepDistance);
            // Every MIME type, not just application/zip. A .quill that arrived over Quick Share or
            // Bluetooth is typed application/octet-stream by the transport that carried it, and a
            // narrower filter would grey out exactly the files this exists to open. The real check
            // is BundleReader's, after the file is opened — the filter here is only a hint.
            importPicker.launch(new String[]{"*/*"});
        });

        // A board created here is standalone (no parent note) — it's owned by Home's Whiteboards
        // section. The row is inserted up front so the board exists in that list even if the user
        // backs out without drawing anything.
        fabOptionWhiteboard.setOnClickListener(v -> {
            collapseFabOptions(fabOptions, sweepDistance);
            createWhiteboard();
        });

        view.findViewById(R.id.fab_option_join).setOnClickListener(v -> {
            collapseFabOptions(fabOptions, sweepDistance);
            joinWhiteboardSession();
        });
    }

    // ── Joining someone else's whiteboard ────────────────────────────────

    /** Permission, scan, token — the same flow the whiteboard's Collaborate button runs. */
    private final CollabEntry collabEntry = new CollabEntry(this);

    /**
     * Scans a host's code and opens a board already joined to their session.
     *
     * <p>Permissions first, then the scan, then the board — in that order because each step is a
     * chance for the user to back out, and the board is the only one of the three that leaves
     * anything behind. It is created with {@code created_now}, so a join that is refused or fails
     * takes the empty board with it (see {@code WhiteboardFragment.discardIfNeverUsed}).
     */
    private void joinWhiteboardSession() {
        // A session outlives the board it was started on, so there may well be one running behind
        // this screen. Joining another ends it — for everyone, if this device is its host — and
        // that is not something to do to a room full of people without saying so first.
        if (CollabSessionHolder.isActive()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.collab_already_in_session_title)
                    .setMessage(CollabSessionHolder.isHost()
                            ? R.string.collab_already_hosting_message
                            : R.string.collab_already_joined_message)
                    .setPositiveButton(R.string.collab_leave_and_join, (d, w) -> scanAndJoin())
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }
        scanAndJoin();
    }

    private void scanAndJoin() {
        collabEntry.scanForToken(this::openJoinedWhiteboard);
    }

    /**
     * A fresh board for the session to fill: the host sends everything it holds on connect.
     *
     * <p><b>No {@code whiteboard_id}.</b> The screen mints its own row when opened without one,
     * which is what a joiner needs, and it is what the {@code quill://} link path has always done.
     * This used to create the row here first and pass its id — the same outcome by a longer route,
     * with an async hop before the navigation and a reason for Home to know about whiteboard
     * background preferences. Two paths to one destination that both worked is worse than one:
     * nobody would have noticed them drifting.
     */
    private void openJoinedWhiteboard(String token) {
        if (!isAdded()) return;
        Bundle args = new Bundle();
        args.putBoolean(WhiteboardFragment.ARG_CREATED_NOW, true);
        args.putString(WhiteboardFragment.ARG_JOIN_TOKEN, token);
        NavHostFragment.findNavController(this).navigate(R.id.whiteboardFragment, args);
    }

    // ── The three things Home can add ────────────────────────────────────
    //
    // Methods rather than listener bodies because there are now two ways to reach each: the FAB's
    // options, and tapping the section header the new thing would land in.

    private void createNote() {
        NavHostFragment.findNavController(this).navigate(R.id.noteEditorFragment);
    }

    private void createCollection() {
        CollectionDialogs.showCreateDialog(requireContext(), name ->
                collectionRepository.createCollection(
                        name, ColorUtils.randomPaletteColor(requireContext()), id -> reloadCollections()));
    }

    /**
     * A board created here is standalone (no parent note) — it's owned by Home's Whiteboards
     * section. The row is inserted up front so the board exists in that list even if the user backs
     * out without drawing anything.
     *
     * <p>Straight to the canvas, untitled — the board is named from its own toolbar the way a note
     * is named in its editor. Asking for a name before there is anything to name is the wrong
     * order, and an empty dialog field was the common answer anyway.
     */
    private void createWhiteboard() {
        whiteboardRepository.createWhiteboard(null, null,
                mse.quill.ui.whiteboard.WhiteboardPreferences.defaultBackground(requireContext()),
                whiteboardId -> openWhiteboard(whiteboardId, null, true));
    }

    /**
     * Unpacks a picked file, trying each of the three things Quill can share in turn — a note, a
     * whiteboard, a whole collection — and offers to open whatever came in.
     *
     * <p>The picker's filter is {@code *&#47;*} (see the FAB listener above), so there is nothing
     * upstream telling this which of the three it is. Each reader rejects a file that isn't its
     * own format ({@link NoteImporter.Failure#NOT_A_BUNDLE}), which is what makes trying them in
     * sequence safe rather than a guess — a {@code .quill} note is rejected by the whiteboard and
     * collection readers for lacking their required entries, and vice versa (see the class docs on
     * {@code WhiteboardBundle} and {@code CollectionBundle} for exactly how each tells the others
     * apart). Order is note, then whiteboard, then collection — notes are the common case.
     *
     * <p>The Snackbar's action is the point. An import lands among however many others are already
     * on Home and, since it arrives with a fresh {@code updated_at}, at the top of its list — but
     * "at the top of a list" is still something to go and find. Offering it directly is one tap,
     * and a Snackbar is right here where a dialog would not be: nothing is lost by missing it,
     * because whatever it is has already been saved.
     */
    /**
     * Entry point for a file Quill was launched to open — tapping a {@code .quill}/{@code
     * .quillboard}/{@code .quillpack} in a file manager, mail client or Quick Share, rather than
     * picking one through the FAB's Import option. {@link mse.quill.MainActivity} delivers the
     * {@code Uri} here once Home is the resumed fragment, so this only ever needs the same
     * three-format cascade {@link #importBundle} already runs for a manually picked file.
     */
    public void handleSharedFile(android.net.Uri source) {
        importBundle(source);
    }

    private void importBundle(android.net.Uri source) {
        Snackbar.make(requireView(), R.string.import_in_progress, Snackbar.LENGTH_SHORT).show();
        noteImporter.importFrom(source, new NoteImporter.OnImported() {
            @Override public void onImported(String noteId, String title) {
                if (!isAdded()) return;
                reloadAll();
                String message = title == null || title.trim().isEmpty()
                        ? getString(R.string.import_succeeded_untitled)
                        : getString(R.string.import_succeeded, title);
                Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG)
                        .setAction(R.string.action_open_import, v -> openNote(noteId))
                        .show();
            }

            @Override public void onFailed(NoteImporter.Failure failure) {
                if (!isAdded()) return;
                importWhiteboardBundle(source);
            }
        });
    }

    private void importWhiteboardBundle(android.net.Uri source) {
        whiteboardImporter.importFrom(source, new mse.quill.data.WhiteboardImporter.OnImported() {
            @Override public void onImported(String whiteboardId, String title) {
                if (!isAdded()) return;
                reloadAll();
                String message = title == null || title.trim().isEmpty()
                        ? getString(R.string.import_succeeded_whiteboard_untitled)
                        : getString(R.string.import_succeeded_whiteboard, title);
                Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG)
                        .setAction(R.string.action_open_import, v -> openWhiteboard(whiteboardId, null))
                        .show();
            }

            @Override public void onFailed(mse.quill.data.WhiteboardImporter.Failure failure) {
                if (!isAdded()) return;
                importCollectionBundle(source);
            }
        });
    }

    private void importCollectionBundle(android.net.Uri source) {
        collectionImporter.importFrom(source, new mse.quill.data.CollectionImporter.OnImported() {
            @Override public void onImported(String collectionId, String name, int imported, int total) {
                if (!isAdded()) return;
                reloadAll();
                String displayName = name == null || name.trim().isEmpty()
                        ? getString(R.string.imported_collection_untitled) : name;
                String message = imported == total
                        ? getString(R.string.import_succeeded_collection, displayName, imported)
                        : getString(R.string.import_succeeded_collection_partial, displayName, imported, total);
                Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG)
                        .setAction(R.string.action_open_import, v -> openCollection(collectionId, displayName))
                        .show();
            }

            @Override public void onFailed(mse.quill.data.CollectionImporter.Failure failure) {
                if (!isAdded()) return;
                Snackbar.make(requireView(),
                        failure == mse.quill.data.CollectionImporter.Failure.NOT_A_BUNDLE
                                ? R.string.import_not_a_bundle : R.string.import_failed,
                        Snackbar.LENGTH_LONG).show();
            }
        });
    }

    /** Sweeps the option buttons up from behind the FAB while fading them in. */
    private void expandFabOptions(View fabOptions, int sweepDistance) {
        fabOptions.animate().cancel();
        fabOptions.setAlpha(0f);
        fabOptions.setTranslationY(sweepDistance);
        fabOptions.setVisibility(View.VISIBLE);
        fabOptions.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(FAB_OPTIONS_ANIM_DURATION_MS)
                .start();
    }

    /** Reverses the sweep — slides the option buttons back down behind the FAB while fading out. */
    private void collapseFabOptions(View fabOptions, int sweepDistance) {
        if (fabOptions.getVisibility() != View.VISIBLE) return;
        fabOptions.animate().cancel();
        fabOptions.animate()
                .alpha(0f)
                .translationY(sweepDistance)
                .setDuration(FAB_OPTIONS_ANIM_DURATION_MS)
                .withEndAction(() -> fabOptions.setVisibility(View.GONE))
                .start();
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadAll();
        // On resume, not just once: Profile is a tab away, so the name can change and come
        // straight back here without this fragment ever being recreated. It is also what moves the
        // header on when the clock crosses into the next part of the day while the app is open.
        renderHeader();
    }

    @Override
    public void onPause() {
        super.onPause();
        // Home is the only screen with a dark header, so the icons it borrowed go back before
        // anything else is drawn.
        setLightStatusBarIcons(true);
    }

    /**
     * Dresses the header for the hour: gradient, ink, status bar, stars and both lines of text.
     *
     * <p>All of it together, because a half-applied period is worse than none — the near-black
     * greeting that reads perfectly on the morning sky disappears into the night one.
     *
     * <p>The two lines are re-drawn from their arrays when the period changes and when the view is
     * built, but not on every resume. Backing out of a note is not a new visit to Home, and a
     * greeting that reshuffled itself each time you closed a note would read as a glitch rather
     * than as variety.
     */
    private void renderHeader() {
        View root = requireView();
        TimeOfDay period = TimeOfDay.now();
        if (period != headerPeriod) {
            headerPeriod = period;
            pickHeaderLines(period);
        }

        root.findViewById(R.id.home_header).setBackgroundResource(period.sky.backgroundRes);
        root.findViewById(R.id.status_bar_scrim)
                .setBackgroundColor(ContextCompat.getColor(requireContext(), period.sky.scrimColourRes));
        root.findViewById(R.id.home_header_stars)
                .setVisibility(period.sky.showsStars ? View.VISIBLE : View.GONE);
        applyStatusBarIcons(period);

        int ink = ContextCompat.getColor(requireContext(), period.sky.inkColourRes);
        String[] greetings = getResources().getStringArray(period.greetingsArrayRes);
        String[] subtitles = getResources().getStringArray(period.subtitlesArrayRes);

        String name = ProfilePreferences.displayName(requireContext());
        String greeting = greetings[greetingIndex % greetings.length];
        TextView greetingView = root.findViewById(R.id.home_greeting);
        greetingView.setText(name == null
                ? greeting
                : getString(R.string.home_greeting_with_name, greeting, name));
        greetingView.setTextColor(ink);

        TextView subtitleView = root.findViewById(R.id.home_greeting_subtitle);
        subtitleView.setText(subtitles[subtitleIndex % subtitles.length]);
        // The same ink, softened. The design draws both lines in one colour; the fade is what keeps
        // the second one secondary without introducing a fourth colour per period that would have
        // to be legible against three different skies.
        subtitleView.setTextColor(ink);
        subtitleView.setAlpha(SUBTITLE_ALPHA);
    }

    private void pickHeaderLines(TimeOfDay period) {
        greetingIndex = RANDOM.nextInt(getResources().getStringArray(period.greetingsArrayRes).length);
        subtitleIndex = RANDOM.nextInt(getResources().getStringArray(period.subtitlesArrayRes).length);
    }

    /**
     * Flips the system's status-bar icons to pale for the night header and back afterwards.
     *
     * <p>The window is the activity's, so this has to be put back — {@link #onPause} does it — or
     * every other screen in the app would inherit Home's night icons and lose its clock against the
     * white page.
     */
    private void applyStatusBarIcons(TimeOfDay period) {
        setLightStatusBarIcons(!period.sky.lightStatusBarIcons);
    }

    private void setLightStatusBarIcons(boolean darkIcons) {
        Window window = requireActivity().getWindow();
        new WindowInsetsControllerCompat(window, window.getDecorView())
                .setAppearanceLightStatusBars(darkIcons);
    }

    private void openNote(String noteId) {
        Bundle args = new Bundle();
        args.putString(NoteEditorFragment.ARG_NOTE_ID, noteId);
        NavHostFragment.findNavController(this).navigate(R.id.noteEditorFragment, args);
    }

    private void openCollection(String collectionId, String displayName) {
        Bundle args = new Bundle();
        args.putString(CollectionDetailFragment.ARG_COLLECTION_ID, collectionId);
        args.putString(CollectionDetailFragment.ARG_COLLECTION_NAME, displayName);
        NavHostFragment.findNavController(this).navigate(R.id.collectionDetailFragment, args);
    }

    private void openWhiteboard(String whiteboardId, String noteId) {
        openWhiteboard(whiteboardId, noteId, false);
    }

    /** @param createdNow true when the row was made a moment ago by the caller, which is what lets
     *                    the board screen discard it again if nothing is drawn on it. */
    private void openWhiteboard(String whiteboardId, String noteId, boolean createdNow) {
        if (!isAdded()) return;
        Bundle args = new Bundle();
        args.putString(WhiteboardFragment.ARG_WHITEBOARD_ID, whiteboardId);
        args.putString(WhiteboardFragment.ARG_NOTE_ID, noteId);
        args.putBoolean(WhiteboardFragment.ARG_CREATED_NOW, createdNow);
        NavHostFragment.findNavController(this).navigate(R.id.whiteboardFragment, args);
    }

    private void showManageWhiteboardDialog(Whiteboard whiteboard) {
        WhiteboardDialogs.showManageDialog(requireContext(), whiteboard, new WhiteboardDialogs.ManageListener() {
            @Override public void onRename() {
                WhiteboardDialogs.showRenameDialog(requireContext(), whiteboard.title, newTitle ->
                        whiteboardRepository.renameWhiteboard(
                                whiteboard.id, newTitle, HomeFragment.this::reloadWhiteboards));
            }

            @Override public void onDelete() {
                // The count is a database read, so the dialog waits for it rather than opening
                // with a warning it might have to add a moment later.
                AppExecutors.getInstance().diskIO(() -> {
                    int embedded = whiteboardRepository.embeddingNoteCountSync(whiteboard.id);
                    AppExecutors.getInstance().mainThread(() -> {
                        if (!isAdded()) return;
                        WhiteboardDialogs.showDeleteConfirmation(
                                requireContext(), whiteboard, embedded,
                                () -> deleteWhiteboardWithUndo(whiteboard));
                    });
                });
            }
        });
    }

    /** Read off the list the cards were built from, which is the same read that drew their
     *  padlocks — so the gate and the badge can never disagree about a given card. */
    private boolean isCollectionLocked(String collectionId) {
        for (Collection c : allCollections) {
            if (c.id.equals(collectionId)) return c.biometricLocked;
        }
        return false;
    }

    private void showManageCollectionDialog(Collection collection) {
        CollectionDialogs.showManageDialog(requireContext(), collection, new CollectionDialogs.ManageListener() {
            @Override public void onRename() {
                CollectionDialogs.showRenameDialog(requireContext(), collection.name, newName ->
                        collectionRepository.renameCollection(collection.id, newName, HomeFragment.this::reloadCollections));
            }

            @Override public void onToggleLock() {
                if (collection.biometricLocked) {
                    CollectionLockFlow.removeLock(requireActivity(), collection,
                            HomeFragment.this::reloadAll);
                } else {
                    CollectionLockFlow.lock(requireActivity(), collection,
                            HomeFragment.this::reloadAll);
                }
            }

            @Override public void onDelete() {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.delete_collection_title_format, collection.name))
                        .setMessage(R.string.delete_collection_message)
                        .setPositiveButton(R.string.action_delete, (d, w) ->
                                deleteCollectionWithUndo(collection))
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            }
        });
    }

    private void togglePin(Note note, boolean isPinned) {
        if (isPinned) {
            noteRepository.unpinNote(note.id, this::reloadAll);
            return;
        }
        noteRepository.pinNote(note.id, new NoteStore.OnPinResult() {
            @Override public void onPinned() { reloadAll(); }

            @Override public void onLimitReached() {
                if (isAdded()) {
                    Toast.makeText(requireContext(), R.string.pin_limit_reached_message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void reloadAll() {
        reloadCollections();
        reloadWhiteboards();
        reloadNotes();
        reloadPinnedNotes();
        reloadTags();
    }

    private void reloadWhiteboards() {
        whiteboardRepository.loadWhiteboards(whiteboards -> {
            if (!isAdded()) return;
            allWhiteboards = whiteboards;
            applyFilters();
        });
    }

    private void reloadPinnedNotes() {
        noteRepository.loadPinnedNotes(notes -> {
            if (!isAdded()) return;
            renderPinnedSection(notes);
            rememberPinnedCount(notes.size());
        });
    }

    /**
     * Fills the pinned band with grey cards before the real ones have been read.
     *
     * <p>The band is built from a database read, so on the way back from a note it appeared a
     * frame or two after everything else and shoved the whole page down as it did — the jitter.
     * Reserving the space first removes the movement rather than hiding it, which is why this
     * draws placeholder cards and not a spinner: a spinner is a different height from what
     * replaces it, so it would jump too.
     *
     * <p>Keyed on the count from last time. It only has to be <em>close</em> — every card is the
     * same fixed height, so the band's height is right even when the number is not, and the number
     * is only wrong for as long as the read takes. Zero means draw nothing, which is also correct:
     * someone with no pinned notes should not see a band flash past on every visit.
     */
    private void showPinnedPlaceholders() {
        int expected = rememberedPinnedCount();
        if (expected <= 0) {
            pinnedSection.setVisibility(View.GONE);
            return;
        }
        pinnedCardsContainer.removeAllViews();
        pinnedSection.setVisibility(View.VISIBLE);
        for (int i = 0; i < expected; i++) {
            pinnedCardsContainer.addView(PinnedNoteCardView.buildPlaceholder(requireContext()));
        }
    }

    private int rememberedPinnedCount() {
        return requireContext().getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_PINNED_COUNT, 0);
    }

    private void rememberPinnedCount(int count) {
        requireContext().getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_PINNED_COUNT, count).apply();
    }

    private void renderPinnedSection(List<Note> pinnedNotes) {
        // Filtered before the visibility check, or unpinning the last pinned note by deleting it
        // would leave the band on screen as an empty strip under its own header.
        pinnedNotes = withoutHidden(pinnedNotes, note -> noteKey(note.id));
        pinnedCardsContainer.removeAllViews();
        pinnedSection.setVisibility(pinnedNotes.isEmpty() ? View.GONE : View.VISIBLE);

        PinnedNoteCardView.Listener listener = new PinnedNoteCardView.Listener() {
            @Override public void onClicked(Note note) { openNote(note.id); }

            @Override public void onLongPressed(Note note) {
                CollectionDialogs.showNoteActionsDialog(requireContext(), allCollections, note.collectionId, true,
                        collectionId -> noteRepository.assignCollection(note.id, collectionId, HomeFragment.this::reloadAll),
                        () -> togglePin(note, true),
                        () -> deleteNoteWithUndo(note));
            }
        };

        for (Note note : pinnedNotes) {
            pinnedCardsContainer.addView(PinnedNoteCardView.build(requireContext(), note, listener));
        }
    }

    private void reloadCollections() {
        collectionRepository.loadCollections(collections -> {
            if (!isAdded()) return;
            allCollections = collections;
            applyFilters();
        });
    }

    private void reloadNotes() {
        noteRepository.loadNotes(null, notes -> {
            if (!isAdded()) return;
            allNotes = notes;
            applyFilters();
        });
    }

    /** Re-runs the filter and redraws the chip row — everything that changing a filter implies. */
    private void onFilterChanged() {
        applyFilters();
        searchBar.render(filter, allTags);
    }

    /**
     * Everything deleted from Home is deferred behind an undo bar, so the lists have to leave those
     * rows out until the bar has gone one way or the other. Done here rather than at each of the
     * three reloads because this is the one place all three lists are handed to the adapter — and
     * because Home reloads itself constantly (resume, filter change, pin, rename), any of which
     * would otherwise put a deleted row back on screen underneath its own undo bar.
     */
    private void applyFilters() {
        homeAdapter.submitCollections(
                withoutHidden(filter.applyToCollections(allCollections), c -> collectionKey(c.id)));
        // Boards are matched on their *displayed* title, so searching "untitled" finds the unnamed
        // ones — the fallback name is what the card shows.
        homeAdapter.submitWhiteboards(withoutHidden(
                filter.applyToWhiteboards(allWhiteboards,
                        board -> NoteDisplayUtils.resolveWhiteboardTitle(requireContext(), board)),
                board -> whiteboardKey(board.id)));
        homeAdapter.submitNotes(withoutHidden(filter.apply(allNotes), note -> noteKey(note.id)));
    }

    private static <T> List<T> withoutHidden(List<T> items, java.util.function.Function<T, String> key) {
        List<T> visible = new ArrayList<>();
        for (T item : items) {
            if (!UndoDelete.isHidden(key.apply(item))) visible.add(item);
        }
        return visible;
    }

    // ── Deleting, with a few seconds to change your mind ──────────────────

    private void deleteNoteWithUndo(Note note) {
        UndoDelete.offer(requireView(), getString(R.string.note_deleted), noteKey(note.id),
                this::reloadAll,
                () -> noteRepository.deleteNote(note.id, null));
        reloadAll();
    }

    private void deleteWhiteboardWithUndo(Whiteboard whiteboard) {
        UndoDelete.offer(requireView(), getString(R.string.whiteboard_deleted),
                whiteboardKey(whiteboard.id),
                this::reloadWhiteboards,
                () -> whiteboardRepository.deleteWhiteboard(whiteboard.id, null));
        reloadWhiteboards();
    }

    private void deleteCollectionWithUndo(Collection collection) {
        UndoDelete.offer(requireView(), getString(R.string.collection_deleted),
                collectionKey(collection.id),
                this::reloadAll,
                () -> collectionRepository.deleteCollection(collection.id, null));
        reloadAll();
    }

    private static String noteKey(String id) { return "note:" + id; }
    private static String whiteboardKey(String id) { return "board:" + id; }
    private static String collectionKey(String id) { return "collection:" + id; }

    private void reloadTags() {
        tagRepository.loadAllTags(tags -> {
            if (!isAdded()) return;
            allTags = tags;
            // A tag deleted elsewhere would otherwise stay in the filter as a chip that can't be
            // resolved to a name, silently hiding every note.
            for (String selected : new ArrayList<>(filter.tagIds())) {
                boolean stillExists = false;
                for (Tag tag : allTags) {
                    if (tag.id.equals(selected)) { stillExists = true; break; }
                }
                if (!stillExists) filter.removeTag(selected);
            }
            onFilterChanged();
        });
    }
}
