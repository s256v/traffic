package org.fc0.traffic;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;

/**
 * Wire format of one UDP packet. Numbers are big-endian.
 *
 * <pre>
 * offset size field
 *  0      4   magic "TRAF"
 *  4      1   version
 *  5      1   flags: FROM_CLIENT, HAS_ECHO
 *  6      2   packet size the sender uses, in bytes
 *  8      8   stream id, random for each sending stream; a new id restarts loss tracking
 * 16      8   sequence number, counting from 0
 * 24      8   send time on the sender's monotonic clock, in nanoseconds
 * 32      8   echo time: send time of the latest packet received from the other side
 * 40      8   echo delay: nanoseconds between receiving that packet and sending this one
 * 48      8   rate in bits per second (client: the rate it asks for, server: the rate it sends at)
 * 56      1   id length in bytes
 * 57      n   client id or server id, UTF-8
 * 57+n    ..  random bytes up to the packet size
 * </pre>
 *
 * A received packet is decoded into the fields of a reusable {@code Packet}.
 */
final class Packet {
    static final int MAGIC = 0x54524146; // "TRAF"
    static final byte VERSION = 1;
    static final int FLAG_FROM_CLIENT = 1;
    static final int FLAG_HAS_ECHO = 2;
    static final int FIXED_HEADER = 57;
    static final int MAX_ID_BYTES = 64;
    /** Smallest packet that fits the header with the longest id. */
    static final int MIN_SIZE = 128;
    /** Largest UDP payload over IPv4. */
    static final int MAX_SIZE = 65_507;

    boolean fromClient;
    boolean hasEcho;
    int packetSize;
    long streamId;
    long seq;
    long sendTime;
    long echoTime;
    long echoDelay;
    long rate;
    String id;
    /** Number of bytes actually received. */
    int length;

    /**
     * Decodes the bytes between the buffer's position and limit. Returns false, leaving the fields undefined, if they
     * are not a valid packet.
     */
    boolean read(ByteBuffer buf) {
        length = buf.remaining();
        if (length < FIXED_HEADER || buf.getInt() != MAGIC || buf.get() != VERSION) {
            return false;
        }
        int flags = buf.get();
        fromClient = (flags & FLAG_FROM_CLIENT) != 0;
        hasEcho = (flags & FLAG_HAS_ECHO) != 0;
        packetSize = Short.toUnsignedInt(buf.getShort());
        streamId = buf.getLong();
        seq = buf.getLong();
        sendTime = buf.getLong();
        echoTime = buf.getLong();
        echoDelay = buf.getLong();
        rate = buf.getLong();
        int idLength = Byte.toUnsignedInt(buf.get());
        if (seq < 0 || rate <= 0 || idLength == 0 || idLength > MAX_ID_BYTES || idLength > buf.remaining()) {
            return false;
        }
        byte[] idBytes = new byte[idLength];
        buf.get(idBytes);
        id = new String(idBytes, StandardCharsets.UTF_8);
        return true;
    }

    /** Builds the packets of one sending stream. Not thread-safe: use one writer per sending thread. */
    static final class Writer {
        private final ByteBuffer buf = ByteBuffer.allocateDirect(MAX_SIZE);
        private final SplittableRandom random = new SplittableRandom();
        private final byte flags;
        private final byte[] id;
        private final long streamId = random.nextLong();
        private long seq;

        Writer(boolean fromClient, String id) {
            this.flags = (byte) (fromClient ? FLAG_FROM_CLIENT : 0);
            this.id = id.getBytes(StandardCharsets.UTF_8);
            if (this.id.length == 0 || this.id.length > MAX_ID_BYTES) {
                throw new IllegalArgumentException("id must be 1 to " + MAX_ID_BYTES + " bytes long");
            }
        }

        /**
         * Returns the next packet of {@code size} bytes, ready to send. {@code echo} may be null when nothing has been
         * received from the other side yet.
         */
        ByteBuffer next(int size, long rate, long now, StreamReceiver.Echo echo) {
            size = Math.max(Math.min(size, MAX_SIZE), FIXED_HEADER + id.length);
            buf.clear();
            buf.putInt(MAGIC);
            buf.put(VERSION);
            buf.put((byte) (echo == null ? flags : flags | FLAG_HAS_ECHO));
            buf.putShort((short) size);
            buf.putLong(streamId);
            buf.putLong(seq++);
            buf.putLong(now);
            buf.putLong(echo == null ? 0 : echo.peerSendTime());
            buf.putLong(echo == null ? 0 : now - echo.receivedAt());
            buf.putLong(rate);
            buf.put((byte) id.length);
            buf.put(id);
            // Random filler, so nothing on the path can compress the traffic.
            while (buf.position() + Long.BYTES <= size) {
                buf.putLong(random.nextLong());
            }
            while (buf.position() < size) {
                buf.put((byte) random.nextInt());
            }
            return buf.flip();
        }
    }
}
