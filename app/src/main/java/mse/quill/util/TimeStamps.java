package mse.quill.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Filename timestamps, formatted the one way so exports sort next to each other. */
public final class TimeStamps {

    private TimeStamps() {}

    /** {@code yyyyMMdd_HHmmss} — sortable, and safe in a filename on any filesystem. */
    public static String fileStamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }
}
