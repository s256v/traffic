package org.fc0.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OutageMeterTest {
    private static final long MS = 1_000_000;
    /** 1.2m with 1200-byte packets: one packet every 8 ms. */
    private static final long INTERVAL = 8 * MS;

    private double seconds;
    private final OutageMeter meter = new OutageMeter(0, s -> seconds += s);

    @Test
    void steadyStreamHasNoOutage() {
        packets(0, 10_000);
        meter.update(10_000 * MS);
        assertEquals(0, seconds);
    }

    @Test
    void shortGapIsNoOutage() {
        packets(0, 1_000);
        assertFalse(meter.onPacket(1_900 * MS, INTERVAL));
        packets(1_908, 3_000);
        assertEquals(0, seconds);
    }

    @Test
    void wholeGapCounts() {
        packets(0, 1_000);
        assertTrue(meter.onPacket(4_000 * MS, INTERVAL), "the first packet after the gap ends the outage");
        assertFalse(meter.onPacket(4_008 * MS, INTERVAL));
        packets(4_016, 5_000);
        assertEquals(3.0, seconds, 0.01);
    }

    @Test
    void outageCountsWhileItLastsAndOnceInTotal() {
        packets(0, 1_000);
        meter.update(1_500 * MS);
        assertEquals(0, seconds);
        meter.update(2_500 * MS);
        assertEquals(1.5, seconds, 0.01);
        meter.update(3_500 * MS);
        assertEquals(2.5, seconds, 0.01);
        packets(4_000, 5_000);
        assertEquals(3.0, seconds, 0.01);
        meter.update(5_000 * MS);
        assertEquals(3.0, seconds, 0.01);
    }

    @Test
    void nothingArrivingAtAllIsAnOutage() {
        meter.update(500 * MS);
        assertEquals(0, seconds);
        meter.update(2_000 * MS);
        assertEquals(2.0, seconds, 0.01);
    }

    @Test
    void lowRatesNeedLongerGaps() {
        // One packet every 2 s: a 2 s gap is normal, a 10 s gap is an outage.
        for (long ms = 0; ms <= 10_000; ms += 2_000) {
            meter.onPacket(ms * MS, 2_000 * MS);
        }
        assertEquals(0, seconds);
        meter.onPacket(20_000 * MS, 2_000 * MS);
        assertEquals(10.0, seconds, 0.01);
    }

    /** Feeds packets from {@code fromMs} up to but not including {@code toMs}. */
    private void packets(long fromMs, long toMs) {
        for (long t = fromMs * MS; t < toMs * MS; t += INTERVAL) {
            meter.onPacket(t, INTERVAL);
        }
    }
}
