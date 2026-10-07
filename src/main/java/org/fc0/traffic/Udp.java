package org.fc0.traffic;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.util.List;

/** Opens the UDP socket of a client or server. */
final class Udp {
    /**
     * Socket buffer size to ask for. Small default buffers (64 KB on Windows) overflow during short pauses of the
     * app, and the packets dropped there would show up as network loss. The OS may give less.
     */
    private static final int BUFFER_BYTES = 4 << 20;

    private Udp() {
    }

    /** Opens a channel on {@code port} of all local addresses. Port 0 picks a free port. */
    static DatagramChannel open(int port) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        try {
            for (SocketOption<Integer> option : List.of(StandardSocketOptions.SO_RCVBUF, StandardSocketOptions.SO_SNDBUF)) {
                try {
                    channel.setOption(option, BUFFER_BYTES);
                } catch (IOException e) {
                    // keep the default size
                }
            }
            channel.bind(new InetSocketAddress(port));
            return channel;
        } catch (BindException e) {
            channel.close();
            throw new BindException("UDP port " + port + " is not available: " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }
}
