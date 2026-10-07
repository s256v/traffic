package org.fc0.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

class MainTest {
    private static final String[] CLIENT = {
        "--mode=client", "--client-id=laptop", "--host=localhost", "--port=5000", "--metrics-port=9102", "--rate-limit=2m"
    };

    @Test
    void printsVersion() {
        StringWriter out = new StringWriter();
        CommandLine commandLine = Main.commandLine();
        commandLine.setOut(new PrintWriter(out));
        assertEquals(0, commandLine.execute("--version"));
        assertTrue(out.toString().contains("traffic " + Main.VERSION));
    }

    @Test
    void clientNeedsItsOptions() {
        assertInvalid("client mode needs --client-id, --host, --rate-limit",
                "--mode=client", "--port=5000", "--metrics-port=9102");
    }

    @Test
    void serverNeedsAnId() {
        assertInvalid("server mode needs --server-id", "--mode=server", "--port=5000", "--metrics-port=9101");
    }

    @Test
    void rejectsOptionsOfTheOtherMode() {
        assertInvalid("--max-rate is a server option", with(CLIENT, "--max-rate=1m"));
        assertInvalid("--rate-limit is a client option",
                "--mode=server", "--server-id=s", "--port=5000", "--metrics-port=9101", "--rate-limit=1m");
        assertInvalid("--client-id is a client option",
                "-m", "server", "-s", "s", "-p", "5000", "-M", "9101", "-c", "c");
    }

    @Test
    void rejectsBadValues() {
        assertInvalid("--rate-limit: invalid rate '2x'", with(CLIENT, "--rate-limit=2x"));
        assertInvalid("--packet-size must be between 128 and 65507", with(CLIENT, "--packet-size=100"));
        assertInvalid("--port must be between 1 and 65535", with(CLIENT, "--port=70000"));
        assertInvalid("--client-id must be 1 to 64 bytes long", with(CLIENT, "--client-id=" + "x".repeat(65)));
        assertInvalid("expected one of [client, server]", "--mode=proxy", "--port=5000", "--metrics-port=9101");
    }

    @Test
    void acceptsShortOptions() {
        ParseResult r = Main.commandLine().parseArgs("-m", "client", "-c", "laptop", "-s", "home", "-M", "9102",
                "-H", "example.com", "-p", "5000", "-r", "2m", "-l", "500", "-R", "5m", "-t", "30");
        assertEquals(Main.Mode.client, r.matchedOptionValue("--mode", null));
        assertEquals("laptop", r.matchedOptionValue("--client-id", null));
        assertEquals("home", r.matchedOptionValue("--server-id", null));
        assertEquals(9102, (int) r.matchedOptionValue("--metrics-port", 0));
        assertEquals("example.com", r.matchedOptionValue("--host", null));
        assertEquals(5000, (int) r.matchedOptionValue("--port", 0));
        assertEquals("2m", r.matchedOptionValue("--rate-limit", null));
        assertEquals(500, (int) r.matchedOptionValue("--packet-size", 0));
        assertEquals("5m", r.matchedOptionValue("--max-rate", null));
        assertEquals(30, (int) r.matchedOptionValue("--client-timeout", 0));
    }

    /** Returns {@code args} with {@code option} added, replacing an earlier value of the same option. */
    private static String[] with(String[] args, String option) {
        String name = option.substring(0, option.indexOf('=') + 1);
        List<String> result = new ArrayList<>(Arrays.stream(args).filter(arg -> !arg.startsWith(name)).toList());
        result.add(option);
        return result.toArray(String[]::new);
    }

    private static void assertInvalid(String expected, String... args) {
        StringWriter err = new StringWriter();
        CommandLine commandLine = Main.commandLine();
        commandLine.setErr(new PrintWriter(err));
        assertEquals(2, commandLine.execute(args), err.toString());
        assertTrue(err.toString().contains(expected), err.toString());
    }
}
