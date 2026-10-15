package org.fc0.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddressesTest {
    @Test
    void parsesIpAddresses() {
        assertEquals("192.0.2.1", Addresses.parse("192.0.2.1").getHostAddress());
        assertEquals("0:0:0:0:0:0:0:1", Addresses.parse("::1").getHostAddress());
        assertEquals("0:0:0:0:0:0:0:1", Addresses.parse("[::1]").getHostAddress());
        assertEquals("2001:db8:0:0:0:0:0:1", Addresses.parse("2001:db8::1").getHostAddress());
        assertNull(Addresses.parse(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "localhost", "example.com", "999.1.1.1", "1.2.3", "01.2.3.4", "1::2::3", "g::1"})
    void rejectsHostNamesAndInvalidAddresses(String text) {
        assertThrows(IllegalArgumentException.class, () -> Addresses.parse(text));
    }

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
