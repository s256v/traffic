The purpose of this application is to debug an internet connection that causes video call interruptions and freezes.

The app can act as either a client or a server.

The app generates bidirectional traffic that simulates a video call and provides a Prometheus metrics endpoint.
The traffic should be random, so that no protocol compression can be applied.
Traffic is sent over UDP, as real video calls are. Each packet carries a sequence number and a timestamp, so that lost packets, jitter, and round-trip time can be measured.
Both the client and the server should provide metrics.

The server should be able to communicate with multiple clients.

When the app starts in client mode, it receives the following parameters:
	- mode=client
	- client-id (any string)
	- metrics-port
	- host
	- port
	- rate-limit in bits per second (e.g. 1k = 1,000, 1m = 1,000,000)
	- packet-size in bytes (default 1200)

When the app starts in server mode, it receives the following parameters:
	- mode=server
	- server-id (any string)
	- metrics-port
	- port
	- max-rate in bits per second (default 10m): the most the server sends to any one client
	- client-timeout in seconds (default 60): after this long without packets from a client, the server drops that client's metrics

The server sends to each client at that client's rate-limit, up to its max-rate. The server uses the client's packet-size.

Metrics provided by both the server and the client:
	- trf_rx_bytes_total
	- trf_tx_bytes_total

	- trf_rx_packets_total
	- trf_tx_packets_total
	- trf_lost_packets_total

	- trf_rx_rate_bits_per_second
	- trf_tx_rate_bits_per_second

	- trf_jitter_seconds (histogram; how much the packet delay varies from one packet to the next)
	- trf_rtt_seconds (histogram; round-trip time: how long a packet takes to reach the other side and for the reply to come back)

Jitter and rtt are histograms, so short spikes between Prometheus scrapes are not lost.
Histogram buckets (seconds):
	- rtt: 0.01, 0.02, 0.03, 0.05, 0.075, 0.1, 0.15, 0.2, 0.25, 0.3, 0.4, 0.5, 0.75, 1, 2, 5
	- jitter: 0.001, 0.002, 0.005, 0.01, 0.02, 0.03, 0.05, 0.1, 0.2, 0.5

Labels:
	- server metrics: client_id, server_id
	- client metrics: client_id

Stack decisions:
	- Terminal app for Linux, Windows, and macOS.
	- Language: Java 25.
	- Build: GraalVM native-image, producing one executable per OS (each built on its own OS).
	- Build tool: Gradle.
	- Metrics: the plain Prometheus Java client (prometheus-metrics-core + prometheus-metrics-exporter-httpserver).
	- Command-line options: picocli.
	- UDP: java.nio DatagramChannel.
