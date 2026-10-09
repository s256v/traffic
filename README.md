# traffic

Sends random UDP traffic both ways between a client and a server, like a video call, and serves
Prometheus metrics on both sides. Use it to see what a connection does to video calls: packet loss,
jitter and round-trip time, in each direction.

## How it works

- The client sends packets to the server at `--rate-limit`. On the first packet, the server starts
  its own stream back to that client at the same rate and packet size, up to its `--max-rate`.
- Each side measures what it receives. The server's loss and jitter describe the upload, the
  client's describe the download. Both sides measure the round-trip time.
- The server drops a client's metrics after `--client-timeout` seconds without packets from it.
- If nothing arrives from the server for 10 s, the client moves to a new UDP port, and again every
  minute while nothing arrives. Some networks block one UDP flow and let a new one through.

## Run

Download the executable for your OS from the Releases page. On Linux and macOS, make it executable
with `chmod +x <file>`. On macOS, also run `xattr -d com.apple.quarantine <file>`: the file isn't
signed, so macOS refuses to open it otherwise.

```
traffic --mode=server --server-id=home
traffic --mode=client --client-id=laptop --host=example.com --rate-limit=2m
```

Stop with Ctrl+C. The server's firewall must let in UDP on `--port` (6123 by default). To run the
client and the server on one machine, give one of them another `--metrics-port`, like `-M 8124`.
The metrics endpoint has no password, so on a public server give it a private or VPN address with
`--metrics-address`.

| Option              | Short | Mode   | Default | Meaning                                            |
|---------------------|-------|--------|---------|----------------------------------------------------|
| `--mode`            | `-m`  | both   |         | `client` or `server`                               |
| `--metrics-port`    | `-M`  | both   | 8123    | port of the Prometheus endpoint, at `/metrics`     |
| `--metrics-address` | `-A`  | both   | all     | IP address of the Prometheus endpoint              |
| `--port`            | `-p`  | both   | 6123    | client: server's UDP port; server: UDP port to use |
| `--client-id`       | `-c`  | client |         | name of this client, the `client_id` label         |
| `--host`            | `-H`  | client |         | server host name or IP address                     |
| `--rate-limit`      | `-r`  | client |         | bits per second, both ways                         |
| `--packet-size`     | `-l`  | client | 1200    | UDP payload size in bytes, both ways               |
| `--server-id`       | `-s`  | server |         | name of this server, the `server_id` label         |
| `--address`         | `-a`  | server | all     | IP address to listen on for UDP                    |
| `--max-rate`        | `-R`  | server | 10m     | the most the server sends to any one client        |
| `--client-timeout`  | `-t`  | server | 60      | seconds without packets before a client is dropped |

Rates are in bits per second: `500k`, `2m`, `2.5m` (k = 1,000, m = 1,000,000, g = 1,000,000,000).

## Metrics

| Metric                                                       | Type      | Meaning                                                      |
|--------------------------------------------------------------|-----------|--------------------------------------------------------------|
| `trf_rx_bytes_total`, `trf_tx_bytes_total`                   | counter   | bytes received, sent                                         |
| `trf_rx_packets_total`, `trf_tx_packets_total`               | counter   | packets received, sent                                       |
| `trf_lost_packets_total`                                     | counter   | packets the other side sent that never arrived               |
| `trf_outage_seconds_total`                                   | counter   | time when nothing at all arrived for over 1 s                |
| `trf_port_changes_total`                                     | counter   | client only: times it moved to a new UDP port                |
| `trf_rx_rate_bits_per_second`, `trf_tx_rate_bits_per_second` | gauge     | receive and send rate over the last second                   |
| `trf_jitter_seconds`                                         | histogram | how much the packet delay varies from one packet to the next |
| `trf_rtt_seconds`                                            | histogram | round-trip time                                              |

Server metrics have the labels `client_id` and `server_id`, client metrics have `client_id`.

- Bytes and rates count the UDP payload. IP and UDP headers (28 bytes per packet over IPv4) are not
  included.
- A missing packet counts as lost once the stream has moved 256 packets past it, so packets that
  only arrive out of order are not counted.
- An outage is a gap of over 1 s with no packets at all (over 3 packet intervals at very low
  rates). The whole gap counts, an outage still going on counts as it goes, and packets missed
  during an outage don't count as lost.

Prometheus scrape config:

```yaml
scrape_configs:
  - job_name: traffic
    static_configs:
      - targets: ["home-server:8123", "laptop:8123"]
```

Queries to start with:

```
# share of packets lost, in percent
100 * rate(trf_lost_packets_total[1m]) / (rate(trf_rx_packets_total[1m]) + rate(trf_lost_packets_total[1m]))

# 95th percentile round-trip time, in seconds
histogram_quantile(0.95, rate(trf_rtt_seconds_bucket[5m]))
```

A Grafana dashboard is in `grafana/dashboard.json`: in Grafana, open Dashboards > New > Import and
upload it. It shows one row per client, with a bar per direction that turns red during an outage
and marks for the client's port changes. Download loss, jitter and outages are measured by the
client, so Prometheus must scrape the client too.

## Build

The build makes a native executable: one file that runs without Java. Build it on each OS and CPU
type you want to run it on, for example on a 64-bit ARM machine for ARM Linux.

You need:

- GraalVM for JDK 25 ([Oracle GraalVM or GraalVM Community](https://www.graalvm.org/downloads/)),
  with `JAVA_HOME` pointing to it.
- A C toolchain:
  - Linux: gcc and the zlib headers, e.g. `sudo apt install build-essential zlib1g-dev`.
  - macOS: `xcode-select --install`.
  - Windows: Visual Studio Build Tools with the "Desktop development with C++" workload.

Then:

```
./gradlew nativeCompile
```

On Windows, run `gradlew nativeCompile`. The executable is `build/native/nativeCompile/traffic`
(`traffic.exe` on Windows).

Tests and a quick run work without GraalVM, with any JDK 17 or newer. Gradle downloads JDK 25 for
them if it is missing.

```
./gradlew test
./gradlew run --args="--mode=server --server-id=home"
```

GitHub Actions builds executables for Linux (x64, ARM64), macOS (Apple silicon) and Windows (x64) on
every push. Download them from the run's Artifacts, or from the Releases page for released versions.

To release, push a version tag:

```
git tag v0.1.0
git push origin v0.1.0
```

GitHub Actions then builds version 0.1.0 and publishes the executables as a release, named like
`traffic-linux-x64`. Other builds are version `dev`; add `-PappVersion=0.1.0` to the Gradle command
to set one.
