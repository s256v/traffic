package org.fc0.traffic;

import java.net.Inet6Address;
import java.net.InetAddress;

/** Parses and formats the IP addresses that sockets listen on. A null address means all local addresses. */
final class Addresses {
    private Addresses() {
    }

    /** Parses an IPv4 or IPv6 address, such as "192.0.2.1" or "::1", without a DNS lookup. Returns null for null. */
    static InetAddress parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid IP address '" + text + "'");
        }
    }

    /** Formats an address for the startup lines: "all interfaces" for null, otherwise the IP address. */
    static String format(InetAddress address) {
        return address == null ? "all interfaces" : address.getHostAddress();
    }

    /** Describes where a socket listens, such as "port 6123 (all interfaces)" or "address 192.0.2.1:6123". */
    static String describe(InetAddress address, int port) {
        return address == null ? "port " + port + " (all interfaces)" : "address " + hostAndPort(address, port);
    }

    private static String hostAndPort(InetAddress address, int port) {
        String host = address.getHostAddress();
        return (address instanceof Inet6Address ? "[" + host + "]" : host) + ":" + port;
    }
}
