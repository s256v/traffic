package org.fc0.traffic;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

/** Timestamped lines on the terminal. */
final class Log {
    private Log() {
    }

    static void info(String format, Object... args) {
        System.out.println(line(format, args));
    }

    /** Prints a title, then one indented line per parameter, such as "  port             6123". */
    static void parameters(String title, Map<String, ?> parameters) {
        StringBuilder text = new StringBuilder(line("%s", title));
        parameters.forEach((name, value) -> text.append(String.format(Locale.ROOT, "%n  %-16s %s", name, value)));
        System.out.println(text);
    }

    static void warn(String format, Object... args) {
        System.err.println(line("warning: " + format, args));
    }

    private static String line(String format, Object... args) {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS) + " " + String.format(Locale.ROOT, format, args);
    }
}
