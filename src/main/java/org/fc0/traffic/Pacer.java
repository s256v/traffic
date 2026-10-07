package org.fc0.traffic;

import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Spaces packets evenly in time so the average rate matches the target.
 *
 * <p>Sending threads are stopped with a flag and {@link LockSupport#unpark}, never with an interrupt: interrupting a
 * thread during a channel operation closes the channel, which is shared by all senders.
 */
final class Pacer {
    /** After falling this far behind, start the schedule again instead of sending a burst to catch up. */
    private static final long MAX_LAG_NANOS = 1_000_000_000L;

    private long next;

    Pacer(long now) {
        next = now;
    }

    /** Sleeps until the next packet is due. Returns false as soon as {@code running} turns false. */
    boolean awaitNext(BooleanSupplier running) {
        while (running.getAsBoolean()) {
            long wait = next - System.nanoTime();
            if (wait <= 0) {
                return true;
            }
            LockSupport.parkNanos(wait);
        }
        return false;
    }

    /** Schedules the next packet after one of {@code bytes} bytes was sent at {@code now}. */
    void sent(int bytes, long bitsPerSecond, long now) {
        next += (long) Math.ceil(bytes * 8 * 1e9 / bitsPerSecond);
        if (now - next > MAX_LAG_NANOS) {
            next = now;
        }
    }
}
