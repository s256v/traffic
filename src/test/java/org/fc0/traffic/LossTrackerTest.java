package org.fc0.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LossTrackerTest {
    private static final int W = LossTracker.WINDOW;

    @Test
    void noLossInOrder() {
        LossTracker t = new LossTracker();
        assertEquals(0, feed(t, 1, 0, 10_000));
    }

    @Test
    void gapCountsOnceItLeavesTheWindow() {
        LossTracker t = new LossTracker();
        assertEquals(0, feed(t, 1, 0, 100));
        // 100..104 missing
        assertEquals(0, feed(t, 1, 105, 100 + W));
        assertEquals(5, feed(t, 1, 100 + W, 105 + W));
        assertEquals(0, feed(t, 1, 105 + W, 2_000));
    }

    @Test
    void reorderedPacketsAreNotLost() {
        LossTracker t = new LossTracker();
        long lost = 0;
        lost += feed(t, 1, 0, 50);
        lost += t.onPacket(1, 51);
        lost += t.onPacket(1, 52);
        lost += t.onPacket(1, 50); // arrives late
        lost += feed(t, 1, 53, 1_000);
        assertEquals(0, lost);
    }

    @Test
    void duplicatesAreIgnored() {
        LossTracker t = new LossTracker();
        long lost = feed(t, 1, 0, 10);
        lost += t.onPacket(1, 5);
        lost += t.onPacket(1, 9);
        lost += feed(t, 1, 10, 1_000);
        assertEquals(0, lost);
    }

    @Test
    void bigGapIsCountedExactly() {
        LossTracker t = new LossTracker();
        feed(t, 1, 0, 10);
        // 10..1009 never arrive: 1000 lost, all known once seq 1010 + W - 1 arrives... the jump alone confirms
        // everything older than the window.
        long lost = t.onPacket(1, 1010);
        lost += feed(t, 1, 1011, 1010 + W);
        assertEquals(1000, lost);
    }

    @Test
    void newStreamStartsOver() {
        LossTracker t = new LossTracker();
        feed(t, 1, 0, 500);
        // The sender restarted: new stream id, sequence numbers from 0 again.
        assertEquals(0, feed(t, 2, 0, 500));
    }

    @Test
    void absurdJumpStartsOver() {
        LossTracker t = new LossTracker();
        feed(t, 1, 0, 10);
        assertEquals(0, t.onPacket(1, 50_000_000));
        assertEquals(0, feed(t, 1, 50_000_001, 50_001_000));
    }

    private static long feed(LossTracker t, long stream, long from, long to) {
        long lost = 0;
        for (long seq = from; seq < to; seq++) {
            lost += t.onPacket(stream, seq);
        }
        return lost;
    }
}
