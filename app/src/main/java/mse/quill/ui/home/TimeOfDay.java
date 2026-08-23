package mse.quill.ui.home;

import java.util.Calendar;

import mse.quill.R;

/**
 * What Home's header looks like and says at a given hour.
 *
 * <p>Two things that don't divide the day the same way, so they are two types. {@link Sky} is the
 * look — the {@code home_morning}, {@code home_day} and {@code home_night} frames in the MSE Figma
 * file, plus an evening palette added afterwards. {@code TimeOfDay} is what the app <em>says</em>,
 * and there can be more of those than there are skies: "Good evening" is fine at eight and wrong
 * at two in the morning, while both of those hours want the same night gradient behind them.
 * Splitting the two is what lets the words get more specific without asking for more palettes.
 *
 * <p>The boundaries are the ordinary meanings of the words rather than astronomical ones. The small
 * hours start at midnight and evening at five, because the header is describing the user's day, not
 * the sky.
 *
 * <p>A period is a whole look — gradient, ink, status bar icons, decoration, and both word lists —
 * so it lives in one place. Spreading those across the fragment is how the navy header ends up
 * wearing the near-black type that only reads on the peach one.
 */
public enum TimeOfDay {

    /** Midnight to five. Not "evening", which is what made 2am read oddly. */
    SMALL_HOURS(0, Sky.NIGHT,
            R.array.home_greetings_small_hours, R.array.home_subtitles_small_hours),

    MORNING(5, Sky.MORNING,
            R.array.home_greetings_morning, R.array.home_subtitles_morning),

    AFTERNOON(12, Sky.DAY,
            R.array.home_greetings_afternoon, R.array.home_subtitles_afternoon),

    EVENING(17, Sky.EVENING,
            R.array.home_greetings_evening, R.array.home_subtitles_evening),

    NIGHT(21, Sky.NIGHT,
            R.array.home_greetings_night, R.array.home_subtitles_night);

    /** The headers the app actually draws — three from the design, plus the evening one. */
    public enum Sky {
        MORNING(R.drawable.bg_home_header_morning, R.color.header_morning_top,
                R.color.header_ink_dark, false, false),
        DAY(R.drawable.bg_home_header_day, R.color.header_day_top,
                R.color.header_ink_dark, false, false),
        /** Its own palette rather than the day's. The day gradient is the open blue of noon, which
         *  says nothing at seven in the evening; this one falls from coral into gold, so the hour
         *  before the navy one actually looks like it. */
        EVENING(R.drawable.bg_home_header_evening, R.color.header_evening_top,
                R.color.header_ink_dark, false, false),
        /** The only dark one, so the only one that changes the ink, the system's status-bar icons
         *  and the sparkle in the corner. */
        NIGHT(R.drawable.bg_home_header_night, R.color.header_night_top,
                R.color.header_ink_light, true, true);

        public final int backgroundRes;
        /** The gradient's top stop, which the status-bar scrim is painted with so the two meet
         *  invisibly rather than as a seam across the top of the screen. */
        public final int scrimColourRes;
        public final int inkColourRes;
        /**
         * Whether the system's status-bar icons have to go pale.
         *
         * <p>The app's theme asks for dark icons everywhere, which is right for every screen but
         * this one after dark: near-black glyphs on {@code #0B5786} are almost invisible, and the
         * clock is the one thing up there the app doesn't get to redraw.
         */
        public final boolean lightStatusBarIcons;
        public final boolean showsStars;

        Sky(int backgroundRes, int scrimColourRes, int inkColourRes,
            boolean lightStatusBarIcons, boolean showsStars) {
            this.backgroundRes = backgroundRes;
            this.scrimColourRes = scrimColourRes;
            this.inkColourRes = inkColourRes;
            this.lightStatusBarIcons = lightStatusBarIcons;
            this.showsStars = showsStars;
        }
    }

    /** First hour this period covers, on a 24-hour clock. Periods are declared in order and each
     *  runs until the next one starts, so the table above is the whole schedule. */
    private final int startHour;

    public final Sky sky;
    public final int greetingsArrayRes;
    public final int subtitlesArrayRes;

    TimeOfDay(int startHour, Sky sky, int greetingsArrayRes, int subtitlesArrayRes) {
        this.startHour = startHour;
        this.sky = sky;
        this.greetingsArrayRes = greetingsArrayRes;
        this.subtitlesArrayRes = subtitlesArrayRes;
    }

    public static TimeOfDay now() {
        return forHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
    }

    /** Visible for testing, and so the schedule is read off the declaration order rather than
     *  restated as a ladder of comparisons that could drift from it. */
    static TimeOfDay forHour(int hourOfDay) {
        TimeOfDay current = SMALL_HOURS;
        for (TimeOfDay period : values()) {
            if (hourOfDay >= period.startHour) current = period;
        }
        return current;
    }
}
