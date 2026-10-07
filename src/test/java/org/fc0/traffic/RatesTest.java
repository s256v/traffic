package org.fc0.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RatesTest {
    @Test
    void parsesSuffixes() {
        assertEquals(800, Rates.parse("800"));
        assertEquals(1_000, Rates.parse("1k"));
        assertEquals(1_000_000, Rates.parse("1m"));
        assertEquals(2_500_000, Rates.parse("2.5M"));
        assertEquals(1_000_000_000, Rates.parse(" 1g "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "0.0001k", "-1m", "1x", "1d", "1e6", "m", "1.m", "200g"})
    void rejectsInvalidRates(String text) {
        assertThrows(IllegalArgumentException.class, () -> Rates.parse(text));
    }

    @Test
    void formats() {
        assertEquals("999", Rates.format(999));
        assertEquals("1k", Rates.format(1_000));
        assertEquals("2.5m", Rates.format(2_500_000));
        assertEquals("10m", Rates.format(10_000_000));
        assertEquals("1.234567m", Rates.format(1_234_567));
    }
}
