package mse.quill.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * What the user has actually done, day by day — the streak and the calendar on Profile.
 *
 * <p>Read entirely from columns that were already being written: {@code flashcards.last_reviewed_at}
 * and {@code quiz_attempts.finished_at}. Nothing here records anything. A separate "activity" table
 * would be a second source of truth for a fact the review itself already establishes, and the two
 * would drift the first time a card was graded and the write to the log failed.
 *
 * <p><b>Local days, not UTC ones.</b> Bucketing by {@code timestamp / 86400000} would put a card
 * reviewed at 1am on the previous day for anyone east of Greenwich and break a streak the user had
 * every right to keep. {@link ZoneId#systemDefault()} is asked at read time, so someone who studies
 * on holiday sees their days as they lived them.
 *
 * <p>A card only records the <em>last</em> time it was reviewed, so the history thins as cards are
 * reviewed again — a day on which every card has since been re-reviewed shows as empty. That is
 * accepted rather than fixed: fixing it means logging every review, which is the second table this
 * class exists to avoid, and the recent end of the calendar — the part anyone looks at — is right.
 */
public final class StudyHistory {

    /** How far back the calendar goes. Twenty weeks fits a phone's width at a legible cell size. */
    public static final int WEEKS = 20;

    public interface OnLoaded { void onLoaded(StudyHistory history); }

    /** Days with any activity, and how much — the calendar's cells. */
    private final Map<LocalDate, Integer> countsByDay;
    /** Consecutive days up to and including today, or up to yesterday if today is still empty. */
    public final int streakDays;
    public final int reviewedToday;
    public final int busiestDay;

    private StudyHistory(Map<LocalDate, Integer> countsByDay, int streakDays,
                         int reviewedToday, int busiestDay) {
        this.countsByDay = countsByDay;
        this.streakDays = streakDays;
        this.reviewedToday = reviewedToday;
        this.busiestDay = busiestDay;
    }

    public int countOn(LocalDate day) {
        Integer count = countsByDay.get(day);
        return count == null ? 0 : count;
    }

    public static void load(Context context, OnLoaded cb) {
        AppExecutors executors = AppExecutors.getInstance();
        executors.diskIO(() -> {
            StudyHistory history = loadSync(context);
            if (cb != null) executors.mainThread(() -> cb.onLoaded(history));
        });
    }

    private static StudyHistory loadSync(Context context) {
        SQLiteDatabase db = AppDatabase.getInstance(context.getApplicationContext())
                .getReadableDatabase();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate earliest = today.minusWeeks(WEEKS);

        Map<LocalDate, Integer> counts = new HashMap<>();
        // Cards and quizzes both count as studying. A day spent sitting a quiz is not a day off,
        // and a calendar that said so would be arguing with the user about what they did.
        addDays(db, counts, zone, earliest,
                "SELECT last_reviewed_at FROM flashcards WHERE last_reviewed_at IS NOT NULL");
        addDays(db, counts, zone, earliest,
                "SELECT finished_at FROM quiz_attempts WHERE finished_at IS NOT NULL");

        int busiest = 0;
        for (int count : counts.values()) busiest = Math.max(busiest, count);

        // Today not being done yet must not read as a broken streak — it is still early. The run is
        // counted from today when there is something on it, and from yesterday otherwise, so the
        // number only falls once a whole day has gone by untouched.
        LocalDate cursor = counts.containsKey(today) ? today : today.minusDays(1);
        int streak = 0;
        while (counts.containsKey(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }

        Integer todayCount = counts.get(today);
        return new StudyHistory(counts, streak, todayCount == null ? 0 : todayCount, busiest);
    }

    private static void addDays(SQLiteDatabase db, Map<LocalDate, Integer> counts, ZoneId zone,
                                LocalDate earliest, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                LocalDate day = Instant.ofEpochMilli(c.getLong(0)).atZone(zone).toLocalDate();
                if (day.isBefore(earliest)) continue;
                counts.merge(day, 1, Integer::sum);
            }
        }
    }
}
