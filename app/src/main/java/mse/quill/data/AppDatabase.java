package mse.quill.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.VisibleForTesting;



public class AppDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "quill.db";
    /**
     * 6, not 5: both the flashcards branch and the whiteboard branch shipped a "version 4" meaning
     * different things, so the next number has to clear the highest either of them used. See
     * {@link #ensureAdditiveSchema} for why the migration doesn't trust this number alone.
     */
    /** v11 ran {@link #ensureNotesFts} on databases that upgraded past v3 and so never got the
     *  search index onCreate builds; v12 adds quiz_attempt_answers. Both additive. */
    private static final int DATABASE_VERSION = 12;
    private static volatile AppDatabase instance;

    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new AppDatabase(context.getApplicationContext());
        }
        return instance;
    }

    private AppDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    /**
     * Opens a database under a name of the caller's choosing, at the current schema version.
     *
     * <p>Exists for the migration test, and it is the only seam that makes one possible: the real
     * helper is a singleton bound to {@code quill.db}, so a test that seeded an old-shaped database
     * there would be upgrading — and on failure, destroying — the notes actually on the device.
     * Not routed through {@link #getInstance}, so a test connection is never handed to app code.
     */
    @VisibleForTesting
    public static AppDatabase openForTest(Context context, String databaseName) {
        return new AppDatabase(context.getApplicationContext(), databaseName);
    }

    private AppDatabase(Context context, String databaseName) {
        super(context, databaseName, null, DATABASE_VERSION);
    }

    /**
     * Closes the shared connection and deletes the database file. Only {@link mse.quill.data.DataWipe}
     * has any business calling this.
     *
     * <p>The close and the null have to happen before the delete, and together: deleting the file
     * out from under an open {@code SQLiteOpenHelper} leaves the helper holding a handle to
     * something that is no longer there, and leaving the singleton in place would hand that same
     * dead helper to the next caller. Nulling it means the next {@link #getInstance} builds a fresh
     * one, which recreates the schema through {@link #onCreate} — an empty Quill rather than a
     * broken one.
     */
    public static synchronized void destroy(Context context) {
        if (instance != null) {
            instance.close();
            instance = null;
        }
        context.getApplicationContext().deleteDatabase(DATABASE_NAME);
    }

    /**
     * True if this Quill holds anything the user would recognise as theirs — a note, a collection
     * or a whiteboard. <b>Blocking — call from the disk thread.</b>
     *
     * <p>For {@code Onboarding}, which needs to tell a first install from an update. The stored
     * "welcome seen" flag can't answer that on its own: it is missing for someone who has been
     * using Quill since before the welcome screen existed, and showing them an empty-app
     * introduction over a notebook they have been keeping for weeks would be worse than never
     * having built one.
     *
     * <p>Counted across three tables rather than notes alone because none of them is a reliable
     * proxy for the others — a board drawn from Home belongs to no note, and a collection can be
     * made before anything is filed in it.
     */
    public boolean hasAnyContentSync() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT (SELECT COUNT(*) FROM notes) + (SELECT COUNT(*) FROM collections) "
                        + "+ (SELECT COUNT(*) FROM whiteboards)", null)) {
            return c.moveToFirst() && c.getInt(0) > 0;
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    /** Every table and index, for a database being created from nothing — see {@link Schema}. */
    @Override
    public void onCreate(SQLiteDatabase db) {
        Schema.createAll(db);
    }

    /**
     * Never destructive, on any path — see {@link Migrations}.
     *
     * <p>Every upgrade runs the same idempotent, column-driven
     * {@link Migrations#ensureAdditiveSchema}. The only thing the version number decides is
     * whether the pre-Markdown note format has to be converted first — and that conversion reads
     * the old rows rather than dropping them.
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) Migrations.migrateLegacyNotesToMarkdown(db);
        Migrations.ensureAdditiveSchema(db);
    }

    /**
     * Also non-destructive.
     *
     * <p>{@link SQLiteOpenHelper}'s default {@code onDowngrade} throws, which on a real device
     * means installing an older Quill leaves it unable to open its own database — a crash on every
     * launch, with the user's notes intact and unreachable behind it. The schema only ever grows,
     * so an older build simply sees columns it never asks about, and the honest response to a
     * downgrade is to leave the file alone.
     */
    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Migrations.ensureAdditiveSchema(db);
    }
}
