package org.fc0.traffic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class AddressesTest {
    @Test
    void aNewPortIsTheSameIp() {
        assertTrue(Addresses.sameIp(address("203.0.113.7", 51234), address("203.0.113.7", 40000)));
        assertTrue(Addresses.sameIp(address("2001:db8::1", 51234), address("2001:db8::1", 40000)));
    }

    @Test
    void aNewAddressIsADifferentIp() {
        assertFalse(Addresses.sameIp(address("203.0.113.7", 51234), address("198.51.100.4", 51234)));
        assertFalse(Addresses.sameIp(address("2001:db8::1", 51234), address("2001:db8::2", 51234)));
    }

    private static InetSocketAddress address(String ip, int port) {
        return new InetSocketAddress(Addresses.parse(ip), port);
    }
}
