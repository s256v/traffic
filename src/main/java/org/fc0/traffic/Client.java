package org.fc0.traffic;

import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.locks.LockSupport;

/**
 * Client mode: sends a steady stream to the server at the configured rate and measures the stream the server sends
 * back.
 */
final class Client implements AutoCloseable {
    record Config(String clientId, String host, int port, int metricsPort, long rate, int packetSize) {
    }

    /** How long the server may stay silent before the client says so on the terminal. */
    private static final long SILENCE_NANOS = 5_000_000_000L;

    private final Config config;
    private final Metrics metrics = new Metrics("client_id");
    private final Metrics.Peer peer;
    private final StreamReceiver stream;
    private final long startedAt = System.nanoTime();
    private InetSocketAddress server;
    private DatagramChannel channel;
    private HTTPServer http;
    private Thread sender;
    private Thread receiver;
    private Thread housekeeper;
    private volatile boolean running;
    private volatile long lastReceived;
    private volatile String serverId;

    Client(Config config) {
        this.config = config;
        this.peer = metrics.peer(config.clientId());
        this.stream = new StreamReceiver(peer, startedAt);
    }

    void start() throws IOException {
        try {
            server = new InetSocketAddress(InetAddress.getByName(config.host()), config.port());
            channel = Udp.open(0);
            http = metrics.serve(config.metricsPort());
        } catch (IOException e) {
            close();
            throw e;
        }
        Log.info("client %s sending to %s at %s with %d-byte packets, metrics on http://localhost:%d/metrics",
                config.clientId(), server, Rates.format(config.rate()), config.packetSize(), metricsPort());
        running = true;
        receiver = Thread.ofPlatform().name("receiver").start(this::receiveLoop);
        sender = Thread.ofPlatform().name("sender").daemon().start(this::sendLoop);
        housekeeper = Thread.ofPlatform().name("housekeeper").daemon().start(this::housekeepingLoop);
    }

    int metricsPort() {
        return http.getPort();
    }

    private void sendLoop() {
        Packet.Writer writer = new Packet.Writer(true, config.clientId());
        Pacer pacer = new Pacer(System.nanoTime());
        boolean warned = false;
        while (pacer.awaitNext(() -> running)) {
            long now = System.nanoTime();
            ByteBuffer packet = writer.next(config.packetSize(), config.rate(), now, stream.latestEcho());
            int bytes = packet.remaining();
            try {
                channel.send(packet, server);
                peer.sent(bytes);
                warned = false;
            } catch (ClosedChannelException e) {
                return;
            } catch (IOException e) {
                if (!warned) {
                    Log.warn("sending to %s failed: %s", server, e.getMessage());
                    warned = true;
                }
            }
            pacer.sent(bytes, config.rate(), now);
        }
    }

    private void receiveLoop() {
        ByteBuffer buf = ByteBuffer.allocateDirect(65_536);
        Packet p = new Packet();
        long serverRate = 0;
        boolean warned = false;
        while (running) {
            buf.clear();
            try {
                channel.receive(buf);
                warned = false;
            } catch (ClosedChannelException e) {
                break;
            } catch (IOException e) {
                if (running && !warned) {
                    Log.warn("receive failed: %s", e.getMessage());
                    warned = true;
                }
                continue;
            }
            long now = System.nanoTime();
            if (!p.read(buf.flip()) || p.fromClient) {
                continue;
            }
            if (serverId == null || now - lastReceived > SILENCE_NANOS || !p.id.equals(serverId)
                    || p.rate != serverRate) {
                Log.info("receiving from server %s at %s%s", p.id, Rates.format(p.rate),
                        p.rate < config.rate() ? ", limited by the server's max-rate" : "");
            }
            serverId = p.id;
            serverRate = p.rate;
            lastReceived = now;
            stream.onPacket(p, now);
        }
    }

    private void housekeepingLoop() {
        boolean silent = false;
        boolean warnedNoServer = false;
        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            peer.updateRates(now);
            if (serverId == null && !warnedNoServer && now - startedAt > SILENCE_NANOS) {
                Log.warn("no packets from the server yet; check the host, the port and the server's firewall");
                warnedNoServer = true;
            }
            boolean silentNow = serverId != null && now - lastReceived > SILENCE_NANOS;
            if (silentNow && !silent) {
                Log.warn("no packets from server %s for %d s", serverId, SILENCE_NANOS / 1_000_000_000L);
            }
            silent = silentNow;
        }
    }

    @Override
    public void close() {
        running = false;
        if (sender != null) {
            LockSupport.unpark(sender);
        }
        if (housekeeper != null) {
            housekeeper.interrupt();
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException e) {
            // closing anyway
        }
        if (http != null) {
            http.close();
        }
        if (receiver != null) {
            try {
                receiver.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
