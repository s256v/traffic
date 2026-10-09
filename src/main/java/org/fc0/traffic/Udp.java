package org.fc0.traffic;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.nio.channels.UnsupportedAddressTypeException;
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

    /**
     * Opens a channel on {@code port} of {@code address}, or of all local addresses if {@code address} is null. Port 0
     * picks a free port.
     */
    static DatagramChannel open(InetAddress address, int port) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        try {
            for (SocketOption<Integer> option : List.of(StandardSocketOptions.SO_RCVBUF, StandardSocketOptions.SO_SNDBUF)) {
                try {
                    channel.setOption(option, BUFFER_BYTES);
                } catch (IOException e) {
                    // keep the default size
                }
            }
            channel.bind(new InetSocketAddress(address, port));
            return channel;
        } catch (BindException | UnsupportedAddressTypeException e) {
            channel.close();
            // UnsupportedAddressTypeException: an IPv6 address on a machine without IPv6.
            String reason = e instanceof BindException ? e.getMessage() : "Unsupported address type";
            throw new BindException("UDP " + Addresses.describe(address, port) + " is not available: " + reason);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }
}
