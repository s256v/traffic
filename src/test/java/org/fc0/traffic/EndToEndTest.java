package org.fc0.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Runs a server and clients on the loopback interface and checks their metrics. */
class EndToEndTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final String C1 = "{client_id=\"c1\"}";
    private static final String S1 = "{client_id=\"c1\",server_id=\"srv\"}";

    @Test
    void bothSidesMeasureTheirStreams() throws Exception {
        try (Server server = server("10m", 60)) {
            try (Client client = client("c1", server, "1m", 500)) {
                Map<String, Double> c = await(client.metricsPort(), m -> m.getOrDefault("trf_rtt_seconds_count" + C1, 0.0) >= 200
                        && near(m.get("trf_rx_rate_bits_per_second" + C1), 1e6));
                Map<String, Double> s = await(server.metricsPort(), m -> m.getOrDefault("trf_rtt_seconds_count" + S1, 0.0) >= 200
                        && near(m.get("trf_rx_rate_bits_per_second" + S1), 1e6));
                for (var entry : Map.of(C1, c, S1, s).entrySet()) {
                    String labels = entry.getKey();
                    Map<String, Double> m = entry.getValue();
                    assertTrue(m.get("trf_rx_packets_total" + labels) >= 200, labels);
                    assertTrue(m.get("trf_tx_packets_total" + labels) >= 200, labels);
                    // A scrape reads the counters one after another, so a packet may land in between.
                    assertEquals(m.get("trf_rx_packets_total" + labels), m.get("trf_rx_bytes_total" + labels) / 500, 3);
                    assertEquals(m.get("trf_tx_packets_total" + labels), m.get("trf_tx_bytes_total" + labels) / 500, 3);
                    assertEquals(0, m.get("trf_lost_packets_total" + labels));
                    assertTrue(near(m.get("trf_tx_rate_bits_per_second" + labels), 1e6), labels);
                    assertTrue(m.get("trf_jitter_seconds_count" + labels) > 0, labels);
                    // Loopback: the average round trip is far below 100 ms.
                    assertTrue(m.get("trf_rtt_seconds_sum" + labels) / m.get("trf_rtt_seconds_count" + labels) < 0.1);
                    assertTrue(m.containsKey("trf_rtt_seconds_bucket" + labels.replace("}", ",le=\"0.075\"}")),
                            "rtt buckets from TASK.md");
                    assertTrue(m.containsKey("trf_jitter_seconds_bucket" + labels.replace("}", ",le=\"0.002\"}")),
                            "jitter buckets from TASK.md");
                }
            }
        }
    }

    @Test
    void serverCapsItsStreamAtMaxRate() throws Exception {
        try (Server server = server("400k", 60); Client client = client("c1", server, "2m", 500)) {
            await(client.metricsPort(), m -> near(m.get("trf_rx_rate_bits_per_second" + C1), 400e3)
                    && near(m.get("trf_tx_rate_bits_per_second" + C1), 2e6));
            await(server.metricsPort(), m -> near(m.get("trf_rx_rate_bits_per_second" + S1), 2e6)
                    && near(m.get("trf_tx_rate_bits_per_second" + S1), 400e3));
        }
    }

    @Test
    void serverServesSeveralClients() throws Exception {
        try (Server server = server("10m", 60);
                Client a = client("a", server, "300k", 300);
                Client b = client("b", server, "600k", 600)) {
            await(server.metricsPort(), m -> near(m.get("trf_tx_rate_bits_per_second{client_id=\"a\",server_id=\"srv\"}"), 300e3)
                    && near(m.get("trf_tx_rate_bits_per_second{client_id=\"b\",server_id=\"srv\"}"), 600e3));
            await(a.metricsPort(), m -> near(m.get("trf_rx_rate_bits_per_second{client_id=\"a\"}"), 300e3));
            await(b.metricsPort(), m -> near(m.get("trf_rx_rate_bits_per_second{client_id=\"b\"}"), 600e3));
        }
    }

    @Test
    void serverDropsSilentClientsAndCountsNoLossWhenOneRestarts() throws Exception {
        try (Server server = server("10m", 1)) {
            try (Client client = client("c1", server, "500k", 500)) {
                await(server.metricsPort(), m -> m.getOrDefault("trf_rx_packets_total" + S1, 0.0) > 50);
            }
            // A restarted client sends a new stream, which must not count as loss.
            try (Client client = client("c1", server, "500k", 500)) {
                await(client.metricsPort(), m -> m.getOrDefault("trf_rx_packets_total" + C1, 0.0) > 100);
                Map<String, Double> s = await(server.metricsPort(), m -> m.getOrDefault("trf_rx_packets_total" + S1, 0.0) > 100);
                assertEquals(0, s.get("trf_lost_packets_total" + S1));
            }
            Map<String, Double> s = await(server.metricsPort(), m -> !m.containsKey("trf_rx_packets_total" + S1));
            assertFalse(s.keySet().stream().anyMatch(key -> key.contains("client_id=\"c1\"")), s.toString());
        }
    }

    private static Server server(String maxRate, int clientTimeoutSeconds) throws IOException {
        Server server = new Server(new Server.Config("srv", LOOPBACK, 0, LOOPBACK, 0, Rates.parse(maxRate),
                Duration.ofSeconds(clientTimeoutSeconds)));
        server.start();
        return server;
    }

    private static Client client(String id, Server server, String rate, int packetSize) throws IOException {
        Client client = new Client(new Client.Config(id, LOOPBACK.getHostAddress(), server.port(), null, 0,
                Rates.parse(rate), packetSize));
        client.start();
        return client;
    }

    /** True if a measured rate is within 20% of the expected one. */
    private static boolean near(Double actual, double expected) {
        return actual != null && Math.abs(actual - expected) <= expected * 0.2;
    }

    /** Scrapes /metrics until {@code condition} holds and returns that scrape. */
    private static Map<String, Double> await(int port, Predicate<Map<String, Double>> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Map<String, Double> metrics;
        do {
            metrics = scrape(port);
            if (condition.test(metrics)) {
                return metrics;
            }
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        fail("metrics never matched: " + metrics);
        return metrics;
    }

    private static Map<String, Double> scrape(int port) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://" + LOOPBACK.getHostAddress() + ":" + port + "/metrics")).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        Map<String, Double> values = new HashMap<>();
        for (String line : response.body().split("\n")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                int space = line.lastIndexOf(' ');
                values.put(line.substring(0, space), Double.parseDouble(line.substring(space + 1)));
            }
        }
        return values;
    }
}
