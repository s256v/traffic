package org.fc0.traffic;

/**
 * Measures the stream of packets coming from one peer: loss, jitter, round-trip time and outages. {@link #onPacket} is
 * called from the receiving thread only; {@link #latestEcho} may be read from the sending thread, and
 * {@link #updateOutage} is called from the housekeeping thread.
 */
final class StreamReceiver {
    /** The latest packet from the peer, echoed back so the peer can measure the round-trip time. */
    record Echo(long peerSendTime, long receivedAt) {
    }

    private static final long MAX_PLAUSIBLE_NANOS = 60_000_000_000L;

    private final Metrics.Peer metrics;
    /** When this side started sending; echoes of older send times belong to an earlier run and are ignored. */
    private final long sendingSince;
    private final LossTracker loss = new LossTracker();
    private final OutageMeter outages;
    private volatile Echo latestEcho;

    private boolean haveTransit;
    private long transitStreamId;
    private long lastTransit;
    private boolean haveRttEcho;
    private long lastRttEcho;

    StreamReceiver(Metrics.Peer metrics, long sendingSince) {
        this.metrics = metrics;
        this.sendingSince = sendingSince;
        this.outages = new OutageMeter(sendingSince, metrics::outage);
    }

    void onPacket(Packet p, long receivedAt) {
        // The peer sends a packet every packetSize * 8 / rate seconds.
        if (outages.onPacket(receivedAt, p.packetSize * 8_000_000_000L / p.rate)) {
            // The packets missed during an outage count as outage time, not as loss.
            metrics.lost(loss.flush());
        }
        metrics.received(p.length);
        metrics.lost(loss.onPacket(p.streamId, p.seq));

        // Jitter: how much the one-way delay changes from one packet to the next (RFC 3550). The two clocks are not
        // in sync, but their offset cancels out in the difference.
        long transit = receivedAt - p.sendTime;
        if (haveTransit && transitStreamId == p.streamId) {
            metrics.jitter(Math.abs(transit - lastTransit) / 1e9);
        }
        haveTransit = true;
        transitStreamId = p.streamId;
        lastTransit = transit;

        // Round-trip time: the peer echoes our send time and says how long it held it. Only the first packet
        // carrying a given echo is used, so one round trip is counted once.
        if (p.hasEcho && (!haveRttEcho || p.echoTime != lastRttEcho)) {
            haveRttEcho = true;
            lastRttEcho = p.echoTime;
            long rtt = receivedAt - p.echoTime - p.echoDelay;
            if (p.echoTime - sendingSince >= 0 && p.echoDelay >= 0 && p.echoDelay < MAX_PLAUSIBLE_NANOS
                    && rtt >= 0 && rtt < MAX_PLAUSIBLE_NANOS) {
                metrics.rtt(rtt / 1e9);
            }
        }

        latestEcho = new Echo(p.sendTime, receivedAt);
    }

    Echo latestEcho() {
        return latestEcho;
    }

    /** Counts an outage that is still going on. Call about once a second. */
    void updateOutage(long now) {
        outages.update(now);
    }
}
