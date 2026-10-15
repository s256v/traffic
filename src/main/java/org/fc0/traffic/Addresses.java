package org.fc0.traffic;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** Parses and formats the IP addresses that sockets listen on. A null address means all local addresses. */
final class Addresses {
    /** A number from 0 to 255 without leading zeros. */
    private static final String BYTE = "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])";
    /** Four such numbers, such as 192.0.2.1. */
    private static final Pattern IPV4 = Pattern.compile(BYTE + "([.]" + BYTE + "){3}");
    /** Hex digits, colons and dots, starting with a hex digit or a colon, and an optional zone such as %en0. */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]*(%[0-9A-Za-z_.-]+)?");

    private Addresses() {
    }

    /** Parses an IPv4 or IPv6 address, such as "192.0.2.1" or "::1", without a DNS lookup. Returns null for null. */
    static InetAddress parse(String text) {
        if (text == null) {
            return null;
        }
        // InetAddress.getByName looks up anything not written like an IP address in DNS, so check the form first.
        // InetAddress.ofLiteral would do both, but needs Java 22.
        String ipv6 = text.startsWith("[") && text.endsWith("]") ? text.substring(1, text.length() - 1) : text;
        if (IPV4.matcher(text).matches() || (ipv6.contains(":") && IPV6.matcher(ipv6).matches())) {
            try {
                return InetAddress.getByName(text);
            } catch (UnknownHostException e) {
                // Written like an IP address, but isn't one, such as 1::2::3.
            }
        }
        throw new IllegalArgumentException("invalid IP address '" + text + "'");
    }

    /** Formats an address for the startup lines: "all interfaces" for null, otherwise the IP address. */
    static String format(InetAddress address) {
        return address == null ? "all interfaces" : address.getHostAddress();
    }

    /** Describes where a socket listens, such as "port 6123 (all interfaces)" or "address 192.0.2.1:6123". */
    static String describe(InetAddress address, int port) {
        return address == null ? "port " + port + " (all interfaces)" : "address " + hostAndPort(address, port);
    }

    /** Whether two socket addresses have the same IP address, whatever their ports. */
    static boolean sameIp(SocketAddress a, SocketAddress b) {
        return a instanceof InetSocketAddress x && b instanceof InetSocketAddress y
                && x.getAddress().equals(y.getAddress());
    }

    private static String hostAndPort(InetAddress address, int port) {
        String host = address.getHostAddress();
        return (address instanceof Inet6Address ? "[" + host + "]" : host) + ":" + port;
    }
}
