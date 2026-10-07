package org.fc0.traffic;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and formats rates in bits per second, such as "500k", "2.5m" or "1g" (k = 1,000, m = 1,000,000). */
final class Rates {
    private static final Pattern RATE = Pattern.compile("(\\d+(?:\\.\\d+)?)([kmg]?)");
    private static final long MAX_BITS_PER_SECOND = 100_000_000_000L;

    private Rates() {
    }

    static long parse(String text) {
        Matcher m = RATE.matcher(text == null ? "" : text.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "invalid rate '" + text + "': use a number with an optional k, m or g suffix, such as 500k or 2m");
        }
        long multiplier = switch (m.group(2)) {
            case "k" -> 1_000L;
            case "m" -> 1_000_000L;
            case "g" -> 1_000_000_000L;
            default -> 1L;
        };
        BigDecimal bits = new BigDecimal(m.group(1)).multiply(BigDecimal.valueOf(multiplier));
        if (bits.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("rate must be at least 1 bit per second: '" + text + "'");
        }
        if (bits.compareTo(BigDecimal.valueOf(MAX_BITS_PER_SECOND)) > 0) {
            throw new IllegalArgumentException("rate is too high: '" + text + "'");
        }
        return bits.longValue();
    }

    static String format(long bitsPerSecond) {
        if (bitsPerSecond >= 1_000_000_000L) {
            return scaled(bitsPerSecond, 1_000_000_000L) + "g";
        }
        if (bitsPerSecond >= 1_000_000L) {
            return scaled(bitsPerSecond, 1_000_000L) + "m";
        }
        if (bitsPerSecond >= 1_000L) {
            return scaled(bitsPerSecond, 1_000L) + "k";
        }
        return Long.toString(bitsPerSecond);
    }

    private static String scaled(long value, long unit) {
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(unit)).stripTrailingZeros().toPlainString();
    }
}
