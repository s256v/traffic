package org.fc0.traffic;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.datapoints.DistributionDataPoint;
import io.prometheus.metrics.core.datapoints.GaugeDataPoint;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.atomic.LongAdder;

/** The Prometheus metrics of one client or server, with its own registry. */
final class Metrics {
    static final double[] RTT_BUCKETS = {
        0.01, 0.02, 0.03, 0.05, 0.075, 0.1, 0.15, 0.2, 0.25, 0.3, 0.4, 0.5, 0.75, 1, 2, 5
    };
    static final double[] JITTER_BUCKETS = {0.001, 0.002, 0.005, 0.01, 0.02, 0.03, 0.05, 0.1, 0.2, 0.5};

    private final PrometheusRegistry registry = new PrometheusRegistry();
    private final Counter rxBytes;
    private final Counter txBytes;
    private final Counter rxPackets;
    private final Counter txPackets;
    private final Counter lostPackets;
    private final Gauge rxRate;
    private final Gauge txRate;
    private final Histogram jitter;
    private final Histogram rtt;

    Metrics(String... labelNames) {
        rxBytes = counter("trf_rx_bytes_total", "UDP payload bytes received", labelNames);
        txBytes = counter("trf_tx_bytes_total", "UDP payload bytes sent", labelNames);
        rxPackets = counter("trf_rx_packets_total", "Packets received", labelNames);
        txPackets = counter("trf_tx_packets_total", "Packets sent", labelNames);
        lostPackets = counter("trf_lost_packets_total",
                "Packets the other side sent that never arrived (counted on the receiving side)", labelNames);
        rxRate = gauge("trf_rx_rate_bits_per_second", "Receive rate over the last second", labelNames);
        txRate = gauge("trf_tx_rate_bits_per_second", "Send rate over the last second", labelNames);
        jitter = histogram("trf_jitter_seconds",
                "How much the packet delay varies from one packet to the next", JITTER_BUCKETS, labelNames);
        rtt = histogram("trf_rtt_seconds",
                "Round-trip time: how long a packet takes to reach the other side and for the reply to come back",
                RTT_BUCKETS, labelNames);
    }

    /**
     * Starts the HTTP endpoint that serves /metrics on {@code port} of {@code address}, or of all local addresses if
     * {@code address} is null. Port 0 picks a free port.
     */
    HTTPServer serve(InetAddress address, int port) throws IOException {
        try {
            return HTTPServer.builder().inetAddress(address).port(port).registry(registry).buildAndStart();
        } catch (SocketException e) {
            // A BindException, or "Unsupported address type" for an IPv6 address on a machine without IPv6.
            throw new BindException(
                    "metrics " + Addresses.describe(address, port) + " is not available: " + e.getMessage());
        }
    }

    /** Returns the metrics for one set of label values, creating them at zero. */
    Peer peer(String... labelValues) {
        return new Peer(labelValues);
    }

    /** Removes all metrics for one set of label values. */
    void remove(String... labelValues) {
        rxBytes.remove(labelValues);
        txBytes.remove(labelValues);
        rxPackets.remove(labelValues);
        txPackets.remove(labelValues);
        lostPackets.remove(labelValues);
        rxRate.remove(labelValues);
        txRate.remove(labelValues);
        jitter.remove(labelValues);
        rtt.remove(labelValues);
    }

    private Counter counter(String name, String help, String[] labelNames) {
        return Counter.builder().name(name).help(help).labelNames(labelNames).register(registry);
    }

    private Gauge gauge(String name, String help, String[] labelNames) {
        return Gauge.builder().name(name).help(help).labelNames(labelNames).register(registry);
    }

    private Histogram histogram(String name, String help, double[] buckets, String[] labelNames) {
        return Histogram.builder().name(name).help(help).classicOnly().classicUpperBounds(buckets)
                .labelNames(labelNames).register(registry);
    }

    /** The metrics of one peer. Thread-safe. */
    final class Peer {
        private final CounterDataPoint rxBytesPoint;
        private final CounterDataPoint txBytesPoint;
        private final CounterDataPoint rxPacketsPoint;
        private final CounterDataPoint txPacketsPoint;
        private final CounterDataPoint lostPacketsPoint;
        private final GaugeDataPoint rxRatePoint;
        private final GaugeDataPoint txRatePoint;
        private final DistributionDataPoint jitterPoint;
        private final DistributionDataPoint rttPoint;
        private final LongAdder rxTotal = new LongAdder();
        private final LongAdder txTotal = new LongAdder();
        // Only touched by updateRates, which runs on one thread.
        private long lastRx;
        private long lastTx;
        private long lastUpdate;

        private Peer(String[] labelValues) {
            rxBytesPoint = rxBytes.labelValues(labelValues);
            txBytesPoint = txBytes.labelValues(labelValues);
            rxPacketsPoint = rxPackets.labelValues(labelValues);
            txPacketsPoint = txPackets.labelValues(labelValues);
            lostPacketsPoint = lostPackets.labelValues(labelValues);
            rxRatePoint = rxRate.labelValues(labelValues);
            txRatePoint = txRate.labelValues(labelValues);
            jitterPoint = jitter.labelValues(labelValues);
            rttPoint = rtt.labelValues(labelValues);
            rxRatePoint.set(0);
            txRatePoint.set(0);
        }

        void received(int bytes) {
            rxBytesPoint.inc(bytes);
            rxPacketsPoint.inc();
            rxTotal.add(bytes);
        }

        void sent(int bytes) {
            txBytesPoint.inc(bytes);
            txPacketsPoint.inc();
            txTotal.add(bytes);
        }

        void lost(long packets) {
            if (packets > 0) {
                lostPacketsPoint.inc(packets);
            }
        }

        void jitter(double seconds) {
            jitterPoint.observe(seconds);
        }

        void rtt(double seconds) {
            rttPoint.observe(seconds);
        }

        /** Sets the rate gauges from the bytes counted since the previous call. Call about once a second. */
        void updateRates(long now) {
            long rx = rxTotal.sum();
            long tx = txTotal.sum();
            if (lastUpdate != 0 && now > lastUpdate) {
                double seconds = (now - lastUpdate) / 1e9;
                rxRatePoint.set((rx - lastRx) * 8 / seconds);
                txRatePoint.set((tx - lastTx) * 8 / seconds);
            }
            lastRx = rx;
            lastTx = tx;
            lastUpdate = now;
        }
    }
}
