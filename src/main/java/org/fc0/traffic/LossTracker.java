package org.fc0.traffic;

import java.util.Arrays;

/**
 * Counts lost packets of one stream from their sequence numbers. A missing packet counts as lost once {@link #WINDOW}
 * newer sequence numbers have been seen without it, so packets that only arrive out of order are not counted.
 */
final class LossTracker {
    static final int WINDOW = 256;
    /** A bigger forward jump is treated as a new stream rather than as millions of lost packets. */
    private static final long MAX_JUMP = 1_000_000;

    private final long[] slotSeq = new long[WINDOW];
    private final boolean[] slotReceived = new boolean[WINDOW];
    private boolean started;
    private long streamId;
    private long highest;

    /** Records a received packet and returns how many packets are newly known to be lost. */
    long onPacket(long streamId, long seq) {
        if (!started || streamId != this.streamId || seq - highest > MAX_JUMP) {
            restart(streamId, seq);
            return 0;
        }
        if (seq <= highest) {
            // Late or duplicate: fill its slot if it is still inside the window.
            int slot = slot(seq);
            if (slotSeq[slot] == seq) {
                slotReceived[slot] = true;
            }
            return 0;
        }
        long lost = 0;
        long gap = seq - highest;
        if (gap > WINDOW) {
            // Everything in the window moves out, and the numbers between never entered it.
            for (int slot = 0; slot < WINDOW; slot++) {
                lost += evict(slot);
            }
            lost += gap - WINDOW;
            for (long s = seq - WINDOW + 1; s <= seq; s++) {
                put(s, s == seq);
            }
        } else {
            for (long s = highest + 1; s <= seq; s++) {
                lost += evict(slot(s));
                put(s, s == seq);
            }
        }
        highest = seq;
        return lost;
    }

    /**
     * Counts the packets still missing in the window as lost, and starts over with the next packet even if it
     * continues the same stream. Used after an outage, so the packets missed during it don't count as lost.
     */
    long flush() {
        long lost = 0;
        if (started) {
            for (int slot = 0; slot < WINDOW; slot++) {
                lost += evict(slot);
            }
            started = false;
        }
        return lost;
    }

    private void restart(long streamId, long seq) {
        Arrays.fill(slotSeq, -1);
        Arrays.fill(slotReceived, false);
        this.streamId = streamId;
        started = true;
        highest = seq;
        put(seq, true);
    }

    private int evict(int slot) {
        return slotSeq[slot] >= 0 && !slotReceived[slot] ? 1 : 0;
    }

    private void put(long seq, boolean received) {
        int slot = slot(seq);
        slotSeq[slot] = seq;
        slotReceived[slot] = received;
    }

    private static int slot(long seq) {
        return (int) (seq % WINDOW);
    }
}
