package org.fc0.traffic;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * Client mode: sends a steady stream to the server at the configured rate and measures the stream the server sends
 * back.
 */
final class Client implements AutoCloseable {
    /** A null {@code metricsAddress} means all local addresses. */
    record Config(String clientId, String host, int port, InetAddress metricsAddress, int metricsPort, long rate,
            int packetSize) {
    }

    /** How long the server may stay silent before the client says so on the terminal. */
    private static final long SILENCE_NANOS = 5_000_000_000L;
    /**
     * How long the server may stay silent before the client moves to a new UDP port. Some networks block one UDP flow
     * and still let others through, and a new port is a new flow.
     */
    private static final long MOVE_NANOS = 10_000_000_000L;
    /** While nothing arrives, the client moves to yet another port this often. */
    private static final long MOVE_AGAIN_NANOS = 60_000_000_000L;

    private final Config config;
    private final Metrics metrics = new Metrics("client_id");
    private final Metrics.Peer peer;
    private final CounterDataPoint portChanges;
    private final StreamReceiver stream;
    private final long startedAt = System.nanoTime();
    private InetSocketAddress server;
    /** Replaced by a new one when the client moves to a new port. */
    private volatile DatagramChannel channel;
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
        this.portChanges = metrics.portChanges(config.clientId());
        this.stream = new StreamReceiver(peer, startedAt);
    }

    void start() throws IOException {
        try {
            server = new InetSocketAddress(InetAddress.getByName(config.host()), config.port());
            channel = Udp.open(null, 0);
            http = metrics.serve(config.metricsAddress(), config.metricsPort());
        } catch (IOException e) {
            close();
            throw e;
        }
        String ip = server.getAddress().getHostAddress();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("mode", "client");
        parameters.put("client-id", config.clientId());
        // The host as given, and the IP address it resolved to if it's a name, like "example.com (192.0.2.1)".
        parameters.put("host", server.getHostString().equals(ip) ? ip : server.getHostString() + " (" + ip + ")");
        parameters.put("port", config.port());
        parameters.put("metrics-address", Addresses.format(config.metricsAddress()));
        parameters.put("metrics-port", metricsPort());
        parameters.put("rate-limit", Rates.format(config.rate()));
        parameters.put("packet-size", config.packetSize() + " bytes");
        Log.parameters("traffic started", parameters);
        running = true;
        receiver = Thread.ofPlatform().name("receiver").start(this::receiveLoop);
        sender = Thread.ofPlatform().name("sender").daemon().start(this::sendLoop);
        housekeeper = Thread.ofPlatform().name("housekeeper").daemon().start(this::housekeepingLoop);
    }

    int metricsPort() {
        return http.getPort();
    }

    private void sendLoop() {
        Pacer pacer = new Pacer(System.nanoTime());
        DatagramChannel current = null;
        Packet.Writer writer = null;
        boolean warned = false;
        while (pacer.awaitNext(() -> running)) {
            DatagramChannel ch = channel;
            if (ch != current) {
                // Each port gets its own stream, so a packet that never left the old port doesn't count as lost.
                current = ch;
                writer = new Packet.Writer(true, config.clientId());
            }
            long now = System.nanoTime();
            ByteBuffer packet = writer.next(config.packetSize(), config.rate(), now, stream.latestEcho());
            int bytes = packet.remaining();
            try {
                ch.send(packet, server);
                peer.sent(bytes);
                warned = false;
            } catch (ClosedChannelException e) {
                if (ch == channel) {
                    return;
                }
                // The client just moved to a new port: the next packet goes out on it.
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
            DatagramChannel ch = channel;
            buf.clear();
            try {
                ch.receive(buf);
                warned = false;
            } catch (ClosedChannelException e) {
                if (ch == channel) {
                    break;
                }
                // The client just moved to a new port: receive on that one.
                continue;
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
            // lastReceived first: the housekeeper reads serverId, then lastReceived.
            lastReceived = now;
            serverId = p.id;
            serverRate = p.rate;
            stream.onPacket(p, now);
        }
    }

    private void housekeepingLoop() {
        boolean silent = false;
        boolean warnedNoServer = false;
        long movedAt = startedAt;
        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            peer.updateRates(now);
            stream.updateOutage(now);
            String id = serverId;
            long received = lastReceived;
            if (id == null && !warnedNoServer && now - startedAt > SILENCE_NANOS) {
                Log.warn("no packets from the server yet; check the host, the port and the server's firewall");
                warnedNoServer = true;
            }
            boolean silentNow = id != null && now - received > SILENCE_NANOS;
            if (silentNow && !silent) {
                Log.warn("no packets from server %s for %d s", id, SILENCE_NANOS / 1_000_000_000L);
            }
            silent = silentNow;
            // Moves once the server has been silent for MOVE_NANOS, then every MOVE_AGAIN_NANOS while it stays silent.
            // Not before the first packet: then the host, the port or a firewall is wrong, and a new port won't help.
            boolean movedSinceLastPacket = movedAt - received > 0;
            if (id != null && now - received > MOVE_NANOS
                    && (!movedSinceLastPacket || now - movedAt > MOVE_AGAIN_NANOS)) {
                movePort((now - received) / 1_000_000_000L);
                movedAt = now;
            }
        }
    }

    /** Moves to a new UDP port, so that the packets both ways travel as a new flow. */
    private synchronized void movePort(long silentSeconds) {
        if (!running) {
            return;
        }
        DatagramChannel old = channel;
        DatagramChannel fresh;
        try {
            fresh = Udp.open(null, 0);
        } catch (IOException e) {
            Log.warn("moving to a new port failed: %s", e.getMessage());
            return;
        }
        Log.warn("no packets from server %s for %d s, moving from port %d to port %d", serverId, silentSeconds,
                localPort(old), localPort(fresh));
        // The sending and receiving threads switch to the new channel when they see it.
        channel = fresh;
        portChanges.inc();
        try {
            old.close();
        } catch (IOException e) {
            // closing anyway
        }
    }

    /** Returns the channel's local port, or -1 if it can't be read. */
    private static int localPort(DatagramChannel channel) {
        try {
            return ((InetSocketAddress) channel.getLocalAddress()).getPort();
        } catch (IOException e) {
            return -1;
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
        // Holding the lock, so a port move can't open a new channel after this.
        synchronized (this) {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException e) {
                // closing anyway
            }
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
