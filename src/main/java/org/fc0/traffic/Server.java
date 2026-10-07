package org.fc0.traffic;

import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Server mode: answers each client with its own stream at the client's rate, capped by max-rate, and drops a client
 * after client-timeout without packets from it.
 */
final class Server implements AutoCloseable {
    record Config(String serverId, int port, int metricsPort, long maxRate, Duration clientTimeout) {
    }

    private final Config config;
    private final Metrics metrics = new Metrics("client_id", "server_id");
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private DatagramChannel channel;
    private HTTPServer http;
    private Thread receiver;
    private Thread housekeeper;
    private volatile boolean running;

    Server(Config config) {
        this.config = config;
    }

    void start() throws IOException {
        try {
            channel = Udp.open(config.port());
            http = metrics.serve(config.metricsPort());
        } catch (IOException e) {
            close();
            throw e;
        }
        Log.info("server %s listening on UDP port %d, metrics on http://localhost:%d/metrics, max-rate %s",
                config.serverId(), port(), metricsPort(), Rates.format(config.maxRate()));
        running = true;
        receiver = Thread.ofPlatform().name("receiver").start(this::receiveLoop);
        housekeeper = Thread.ofPlatform().name("housekeeper").daemon().start(this::housekeepingLoop);
    }

    int port() throws IOException {
        return ((InetSocketAddress) channel.getLocalAddress()).getPort();
    }

    int metricsPort() {
        return http.getPort();
    }

    private void receiveLoop() {
        ByteBuffer buf = ByteBuffer.allocateDirect(65_536);
        Packet p = new Packet();
        boolean warned = false;
        while (running) {
            SocketAddress from;
            buf.clear();
            try {
                from = channel.receive(buf);
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
            if (!p.read(buf.flip()) || !p.fromClient) {
                continue;
            }
            // Found and marked as alive in one step, so the housekeeper never drops a client that has just sent.
            Session session = sessions.compute(p.id, (id, s) -> s != null ? s.touch(now) : new Session(id, now));
            session.onPacket(p, from, now);
        }
    }

    private void housekeepingLoop() {
        long timeout = config.clientTimeout().toNanos();
        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            for (String clientId : List.copyOf(sessions.keySet())) {
                sessions.computeIfPresent(clientId, (id, session) -> {
                    if (now - session.lastReceived <= timeout) {
                        session.peer.updateRates(now);
                        return session;
                    }
                    session.stop();
                    metrics.remove(id, config.serverId());
                    Log.info("client %s timed out after %d s without packets", id, config.clientTimeout().toSeconds());
                    return null;
                });
            }
        }
    }

    @Override
    public void close() {
        running = false;
        sessions.values().forEach(Session::stop);
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

    /** One connected client: what the server receives from it and the stream it sends back. */
    private final class Session {
        final String clientId;
        final Metrics.Peer peer;
        final StreamReceiver stream;
        final Thread sender;
        volatile long lastReceived;
        private volatile boolean active = true;
        private volatile SocketAddress address;
        private volatile long requestedRate;
        private volatile int packetSize;

        Session(String clientId, long now) {
            this.clientId = clientId;
            this.peer = metrics.peer(clientId, config.serverId());
            this.stream = new StreamReceiver(peer, now);
            this.lastReceived = now;
            this.sender = Thread.ofPlatform().name("sender-" + clientId).daemon().unstarted(this::sendLoop);
        }

        Session touch(long now) {
            lastReceived = now;
            return this;
        }

        /** Called from the receiving thread only. */
        void onPacket(Packet p, SocketAddress from, long now) {
            int size = Math.max(Math.min(p.packetSize, Packet.MAX_SIZE), Packet.MIN_SIZE);
            if (address == null) {
                Log.info("client %s connected from %s, %s", clientId, from, describe(p.rate, size));
            } else {
                if (!from.equals(address)) {
                    Log.info("client %s now sends from %s", clientId, from);
                }
                if (p.rate != requestedRate || size != packetSize) {
                    Log.info("client %s changed to %s", clientId, describe(p.rate, size));
                }
            }
            address = from;
            requestedRate = p.rate;
            packetSize = size;
            stream.onPacket(p, now);
            if (sender.getState() == Thread.State.NEW) {
                sender.start();
            }
        }

        private String describe(long rate, int size) {
            String text = "rate " + Rates.format(rate) + ", packet size " + size;
            return rate > config.maxRate() ? text + ", sending at max-rate " + Rates.format(config.maxRate()) : text;
        }

        private void sendLoop() {
            Packet.Writer writer = new Packet.Writer(false, config.serverId());
            Pacer pacer = new Pacer(System.nanoTime());
            boolean warned = false;
            while (pacer.awaitNext(() -> active && running)) {
                int size = packetSize;
                long rate = Math.min(requestedRate, config.maxRate());
                long now = System.nanoTime();
                ByteBuffer packet = writer.next(size, rate, now, stream.latestEcho());
                int bytes = packet.remaining();
                try {
                    channel.send(packet, address);
                    peer.sent(bytes);
                    warned = false;
                } catch (ClosedChannelException e) {
                    return;
                } catch (IOException e) {
                    if (!warned) {
                        Log.warn("sending to client %s failed: %s", clientId, e.getMessage());
                        warned = true;
                    }
                }
                pacer.sent(bytes, rate, now);
            }
        }

        void stop() {
            active = false;
            LockSupport.unpark(sender);
        }
    }
}
