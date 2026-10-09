package org.fc0.traffic;

import java.util.function.DoubleConsumer;

/**
 * Measures outages: gaps between packets of over 1 s, or of over 3 packet intervals at very low rates. The whole gap
 * counts, from the last packet before it to the first one after it. An outage that is still going on is counted as it
 * goes, so it shows in the metrics before it ends. Thread-safe.
 */
final class OutageMeter {
    static final long MIN_GAP_NANOS = 1_000_000_000L;

    private final DoubleConsumer outageSeconds;
    private long lastPacket;
    private long countedUntil;
    private long minGap = MIN_GAP_NANOS;

    /** {@code start} counts like a packet, so if nothing arrives at all, that's an outage too. */
    OutageMeter(long start, DoubleConsumer outageSeconds) {
        this.outageSeconds = outageSeconds;
        this.lastPacket = start;
        this.countedUntil = start;
    }

    /**
     * Records a packet from a stream that sends one every {@code intervalNanos}. Returns true if it ends an outage.
     */
    synchronized boolean onPacket(long now, long intervalNanos) {
        boolean endsOutage = now - lastPacket > minGap;
        update(now);
        lastPacket = now;
        minGap = Math.max(MIN_GAP_NANOS, 3 * intervalNanos);
        return endsOutage;
    }

    /** Counts an outage that is still going on. Call about once a second. */
    synchronized void update(long now) {
        if (now - lastPacket > minGap) {
            long from = countedUntil - lastPacket > 0 ? countedUntil : lastPacket;
            if (now - from > 0) {
                outageSeconds.accept((now - from) / 1e9);
                countedUntil = now;
            }
        }
    }
}
