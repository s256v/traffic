package org.fc0.traffic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class PacketTest {
    @Test
    void roundTrip() {
        Packet.Writer writer = new Packet.Writer(true, "laptop");
        ByteBuffer first = copy(writer.next(1200, 2_000_000, 111, null));
        ByteBuffer second = copy(writer.next(1200, 2_000_000, 222, new StreamReceiver.Echo(77, 200)));

        Packet p = new Packet();
        assertTrue(p.read(first));
        assertEquals(1200, p.length);
        assertTrue(p.fromClient);
        assertFalse(p.hasEcho);
        assertEquals(1200, p.packetSize);
        assertEquals(0, p.seq);
        assertEquals(111, p.sendTime);
        assertEquals(2_000_000, p.rate);
        assertEquals("laptop", p.id);
        long streamId = p.streamId;

        assertTrue(p.read(second));
        assertEquals(1, p.seq);
        assertEquals(streamId, p.streamId);
        assertTrue(p.hasEcho);
        assertEquals(77, p.echoTime);
        assertEquals(22, p.echoDelay);
    }

    @Test
    void serverPacketsAreMarked() {
        ByteBuffer buf = copy(new Packet.Writer(false, "server-1").next(500, 1_000, 1, null));
        Packet p = new Packet();
        assertTrue(p.read(buf));
        assertFalse(p.fromClient);
        assertEquals("server-1", p.id);
        assertEquals(500, p.length);
    }

    @Test
    void payloadIsRandom() {
        Packet.Writer writer = new Packet.Writer(true, "c");
        byte[] a = bytes(writer.next(1200, 1_000, 1, null));
        byte[] b = bytes(writer.next(1200, 1_000, 1, null));
        byte[] payloadA = java.util.Arrays.copyOfRange(a, 100, 1200);
        byte[] payloadB = java.util.Arrays.copyOfRange(b, 100, 1200);
        assertNotEquals(java.util.Arrays.toString(payloadA), java.util.Arrays.toString(payloadB));
        assertArrayEquals(java.util.Arrays.copyOfRange(a, 0, 4), new byte[] {'T', 'R', 'A', 'F'});
    }

    @Test
    void rejectsForeignData() {
        Packet p = new Packet();
        assertFalse(p.read(ByteBuffer.wrap(new byte[10])));
        assertFalse(p.read(ByteBuffer.wrap(new byte[200])));
        ByteBuffer truncated = copy(new Packet.Writer(true, "laptop").next(1200, 1_000, 1, null));
        truncated.limit(Packet.FIXED_HEADER + 2);
        assertFalse(p.read(truncated));
    }

    private static ByteBuffer copy(ByteBuffer packet) {
        return ByteBuffer.wrap(bytes(packet));
    }

    private static byte[] bytes(ByteBuffer packet) {
        byte[] bytes = new byte[packet.remaining()];
        packet.get(bytes);
        return bytes;
    }
}
