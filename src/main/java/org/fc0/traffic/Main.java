package org.fc0.traffic;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;
import picocli.CommandLine.UnmatchedArgumentException;

@Command(
        name = "traffic",
        mixinStandardHelpOptions = true,
        version = "traffic " + Main.VERSION,
        sortOptions = false,
        usageHelpAutoWidth = true,
        description = "Sends random UDP traffic both ways between a client and a server, like a video call, and "
                + "serves Prometheus metrics on both sides, to debug a connection that makes video calls freeze.%n",
        footerHeading = "%nExamples:%n",
        footer = {
            "  traffic --mode=server --server-id=home --port=5000 --metrics-port=9101",
            "  traffic --mode=client --client-id=laptop --host=example.com --port=5000 --metrics-port=9102 "
                    + "--rate-limit=2m"
        })
public final class Main implements Callable<Integer> {
    static final String VERSION = Version.VALUE;

    enum Mode { client, server }

    @Spec
    private CommandSpec spec;

    @Option(names = {"-m", "--mode"}, required = true, description = "client or server.")
    private Mode mode;

    @Option(names = {"-c", "--client-id"}, paramLabel = "<id>",
            description = "Client: name of this client (client_id label).")
    private String clientId;

    @Option(names = {"-s", "--server-id"}, paramLabel = "<id>",
            description = "Server: name of this server (server_id label).")
    private String serverId;

    @Option(names = {"-M", "--metrics-port"}, required = true, paramLabel = "<port>",
            description = "Port of the Prometheus endpoint, served at /metrics.")
    private int metricsPort;

    @Option(names = {"-H", "--host"}, paramLabel = "<host>", description = "Client: server host name or IP address.")
    private String host;

    @Option(names = {"-p", "--port"}, required = true, paramLabel = "<port>",
            description = "Client: server UDP port. Server: UDP port to listen on.")
    private int port;

    @Option(names = {"-r", "--rate-limit"}, paramLabel = "<rate>",
            description = "Client: rate in bits per second, both ways, e.g. 500k, 1m or 2.5m (k = 1,000, m = 1,000,000).")
    private String rateLimit;

    @Option(names = {"-l", "--packet-size"}, paramLabel = "<bytes>", defaultValue = "1200",
            description = "Client: UDP payload size in bytes, both ways (default: ${DEFAULT-VALUE}).")
    private int packetSize;

    @Option(names = {"-R", "--max-rate"}, paramLabel = "<rate>", defaultValue = "10m",
            description = "Server: the most the server sends to any one client, in bits per second "
                    + "(default: ${DEFAULT-VALUE}).")
    private String maxRate;

    @Option(names = {"-t", "--client-timeout"}, paramLabel = "<seconds>", defaultValue = "60",
            description = "Server: seconds without packets after which a client is dropped "
                    + "(default: ${DEFAULT-VALUE}).")
    private int clientTimeout;

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    static CommandLine commandLine() {
        return new CommandLine(new Main())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setParameterExceptionHandler(Main::invalidInput);
    }

    /** Prints a short error instead of the whole help text. */
    private static int invalidInput(ParameterException e, String[] args) {
        CommandLine commandLine = e.getCommandLine();
        PrintWriter err = commandLine.getErr();
        err.println("error: " + e.getMessage());
        UnmatchedArgumentException.printSuggestions(e, err);
        err.println("Run 'traffic --help' to see all options.");
        err.flush();
        return commandLine.getCommandSpec().exitCodeOnInvalidInput();
    }

    @Override
    public Integer call() throws InterruptedException {
        AutoCloseable app;
        try {
            app = mode == Mode.client ? startClient() : startServer();
        } catch (UnknownHostException e) {
            return fail("unknown host " + host);
        } catch (IOException e) {
            return fail(e.getMessage());
        }
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                app.close();
            } catch (Exception e) {
                // exiting anyway
            }
            stopped.countDown();
        }));
        stopped.await();
        return 0;
    }

    private AutoCloseable startClient() throws IOException {
        rejectOptions("server", "--server-id", "--max-rate", "--client-timeout");
        requireOptions("client", "--client-id", "--host", "--rate-limit");
        checkId("--client-id", clientId);
        checkPort("--port", port);
        checkPort("--metrics-port", metricsPort);
        if (packetSize < Packet.MIN_SIZE || packetSize > Packet.MAX_SIZE) {
            throw invalid("--packet-size must be between " + Packet.MIN_SIZE + " and " + Packet.MAX_SIZE);
        }
        Client client = new Client(
                new Client.Config(clientId, host, port, metricsPort, parseRate("--rate-limit", rateLimit), packetSize));
        client.start();
        return client;
    }

    private AutoCloseable startServer() throws IOException {
        rejectOptions("client", "--client-id", "--host", "--rate-limit", "--packet-size");
        requireOptions("server", "--server-id");
        checkId("--server-id", serverId);
        checkPort("--port", port);
        checkPort("--metrics-port", metricsPort);
        if (clientTimeout < 1) {
            throw invalid("--client-timeout must be at least 1 second");
        }
        Server server = new Server(new Server.Config(serverId, port, metricsPort, parseRate("--max-rate", maxRate),
                Duration.ofSeconds(clientTimeout)));
        server.start();
        return server;
    }

    private int fail(String message) {
        PrintWriter err = spec.commandLine().getErr();
        err.println("error: " + message);
        err.flush();
        return 1;
    }

    private void requireOptions(String modeName, String... names) {
        List<String> missing = List.of(names).stream().filter(name -> !given(name)).toList();
        if (!missing.isEmpty()) {
            throw invalid(modeName + " mode needs " + String.join(", ", missing));
        }
    }

    private void rejectOptions(String otherMode, String... names) {
        for (String name : names) {
            if (given(name)) {
                throw invalid(name + " is a " + otherMode + " option");
            }
        }
    }

    private boolean given(String name) {
        return spec.commandLine().getParseResult().hasMatchedOption(name);
    }

    private void checkId(String name, String id) {
        int bytes = id.getBytes(StandardCharsets.UTF_8).length;
        if (id.isBlank() || bytes > Packet.MAX_ID_BYTES) {
            throw invalid(name + " must be 1 to " + Packet.MAX_ID_BYTES + " bytes long");
        }
    }

    private void checkPort(String name, int value) {
        if (value < 1 || value > 65_535) {
            throw invalid(name + " must be between 1 and 65535");
        }
    }

    private long parseRate(String name, String value) {
        try {
            return Rates.parse(value);
        } catch (IllegalArgumentException e) {
            throw invalid(name + ": " + e.getMessage());
        }
    }

    private ParameterException invalid(String message) {
        return new ParameterException(spec.commandLine(), message);
    }
}
