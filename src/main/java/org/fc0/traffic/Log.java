package org.fc0.traffic;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Timestamped lines on the terminal. */
final class Log {
    private Log() {
    }

    static void info(String format, Object... args) {
        System.out.println(line(format, args));
    }

    static void warn(String format, Object... args) {
        System.err.println(line("warning: " + format, args));
    }

    private static String line(String format, Object... args) {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS) + " " + String.format(Locale.ROOT, format, args);
    }
}
