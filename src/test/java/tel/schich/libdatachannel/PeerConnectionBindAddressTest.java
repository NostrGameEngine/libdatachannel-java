package tel.schich.libdatachannel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Actual JNI/ICE tests, enabled with -Dlibdatachannel.native.tests=true and a built native library.
 * No STUN/TURN servers or address rewriting are used.
 */
@EnabledIfSystemProperty(named = "libdatachannel.native.tests", matches = "true")
class PeerConnectionBindAddressTest {
    private static final byte[] IPV4 = {127, 0, 0, 1};
    private static final int MESSAGES = 128;
    private static final int BYTES = 1024;

    @Test
    void nullBindAddressKeepsNativeDefault() {
        assertNull(PeerConnectionConfiguration.DEFAULT.bindAddress);
        try (PeerConnection peer = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT)) {
            assertNotNull(peer);
        }
    }

    @Test
    void numericIpv4BindsAndGathersItsActualAddress() throws Exception {
        assertBoundCandidate(InetAddress.getByAddress(IPV4));
    }

    @Test
    void namedIpv4UsesItsAddressWithoutResolvingItsName() throws Exception {
        assertBoundCandidate(InetAddress.getByAddress("unresolvable-bind.invalid", IPV4));
    }

    @Test
    void numericIpv6BindsAndGathersItsActualAddress() throws Exception {
        assertBoundCandidate(InetAddress.getByAddress(ipv6Loopback()));
    }

    @Test
    void namedIpv6UsesItsAddressWithoutResolvingItsName() throws Exception {
        assertBoundCandidate(InetAddress.getByAddress("unresolvable-bind.invalid", ipv6Loopback()));
    }

    private static byte[] ipv6Loopback() {
        byte[] address = new byte[16];
        address[15] = 1;
        return address;
    }

    private static void assertBoundCandidate(InetAddress bindAddress) throws Exception {
        PeerConnectionConfiguration configuration = PeerConnectionConfiguration.DEFAULT
                .withBindAddress(bindAddress).withDisableAutoNegotiation(true);
        assertTrue(configuration.iceServers().isEmpty());
        ArrayBlockingQueue<String> candidates = new ArrayBlockingQueue<>(4);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (PeerConnection peer = PeerConnection.createPeer(configuration)) {
            peer.onLocalCandidate.register((active, candidate, media) -> {
                if (!candidates.offer(candidate)) {
                    failure.compareAndSet(null, new AssertionError("Candidate queue exceeded its bound"));
                }
            });
            try (DataChannel channel = peer.createDataChannel("bind-address-regression")) {
                peer.setLocalDescription("offer");
                String candidate = candidates.poll(5, TimeUnit.SECONDS);
                assertNotNull(candidate, "Explicit binding must gather its actual socket address");
                String[] fields = candidate.split(" ");
                assertTrue(fields.length >= 8, candidate);
                assertEquals("host", fields[7]);
                assertArrayEquals(bindAddress.getAddress(), InetAddress.getByName(fields[4]).getAddress());
                assertTrue(Integer.parseInt(fields[5]) > 0);
                assertNull(failure.get());
            }
        }
    }

    @Test
    void explicitIpv4BindingTransfersOrderedBytesBothWays() throws Exception {
        transferOrderedBytesBothWays(MESSAGES, BYTES);
    }

    static void transferOrderedBytesBothWays(int messages, int bytesPerMessage) throws Exception {
        PeerConnectionConfiguration configuration = PeerConnectionConfiguration.DEFAULT
                .withBindAddress(InetAddress.getByAddress(IPV4)).withDisableAutoNegotiation(true);
        assertTrue(configuration.iceServers().isEmpty());
        ArrayBlockingQueue<Signal> signals = new ArrayBlockingQueue<>(64);
        ArrayBlockingQueue<byte[]> echo = new ArrayBlockingQueue<>(messages);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<DataChannel> receiver = new AtomicReference<>();
        AtomicInteger received = new AtomicInteger();
        AtomicInteger returned = new AtomicInteger();
        AtomicInteger descriptions = new AtomicInteger();
        AtomicInteger candidates = new AtomicInteger();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try (PeerConnection left = PeerConnection.createPeer(configuration);
             PeerConnection right = PeerConnection.createPeer(configuration)) {
            left.onLocalDescription.register((peer, data, type) -> {
                descriptions.incrementAndGet();
                enqueue(signals, new Signal(right, data, type, null), failure);
            });
            right.onLocalDescription.register((peer, data, type) -> {
                descriptions.incrementAndGet();
                enqueue(signals, new Signal(left, data, type, null), failure);
            });
            left.onLocalCandidate.register((peer, data, media) -> {
                candidates.incrementAndGet();
                enqueue(signals, new Signal(right, data, null, media), failure);
            });
            right.onLocalCandidate.register((peer, data, media) -> {
                candidates.incrementAndGet();
                enqueue(signals, new Signal(left, data, null, media), failure);
            });
            right.onDataChannel.register((peer, channel) -> {
                if (!receiver.compareAndSet(null, channel)) {
                    failure.compareAndSet(null, new AssertionError("Unexpected extra data channel"));
                    return;
                }
                channel.onError.register((active, message) ->
                        failure.compareAndSet(null, new AssertionError(message)));
                channel.onMessage.register(DataChannelCallback.Message.handleBinary((active, bytes) -> {
                    try {
                        byte[] copy = checkMessage(bytes, received.get(), messages, bytesPerMessage);
                        received.incrementAndGet();
                        assertTrue(echo.offer(copy), "Echo queue exceeded its bound");
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                    }
                }));
            });
            try (DataChannel sender = left.createDataChannel("explicit-loopback-regression")) {
                sender.onError.register((active, message) ->
                        failure.compareAndSet(null, new AssertionError(message)));
                sender.onMessage.register(DataChannelCallback.Message.handleBinary((active, bytes) -> {
                    try {
                        checkMessage(bytes, returned.get(), messages, bytesPerMessage);
                        returned.incrementAndGet();
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                    }
                }));
                left.setLocalDescription("offer");
                boolean sent = false;
                while (System.nanoTime() < deadline && returned.get() < messages) {
                    assertNull(failure.get(), "Native callback failed");
                    Signal signal;
                    while ((signal = signals.poll()) != null) {
                        if (signal.type != null) {
                            signal.target.setRemoteDescription(signal.data, signal.type);
                            if (signal.type == SessionDescriptionType.OFFER) {
                                signal.target.setLocalDescription("answer");
                            }
                        } else {
                            signal.target.addRemoteCandidate(signal.data, signal.mediaId);
                        }
                    }
                    DataChannel target = receiver.get();
                    if (!sent && sender.isOpen() && target != null && target.isOpen()) {
                        assertLoopback(left.localAddress());
                        assertLoopback(left.remoteAddress());
                        assertLoopback(right.localAddress());
                        assertLoopback(right.remoteAddress());
                        for (int index = 0; index < messages; index++) {
                            sender.sendMessage(direct(payload(index, bytesPerMessage)));
                        }
                        sent = true;
                    }
                    byte[] next;
                    while (target != null && target.isOpen() && (next = echo.poll()) != null) {
                        target.sendMessage(direct(next));
                    }
                    Thread.sleep(2);
                }
                assertTrue(sent, "Explicitly bound native channel did not open within its bound");
                assertEquals(messages, received.get());
                assertEquals(messages, returned.get());
                assertTrue(descriptions.get() >= 2);
                assertTrue(candidates.get() >= 2);
                assertNull(failure.get());
                assertEquals(0, sender.bufferedAmount());
                assertEquals(0, receiver.get().bufferedAmount());
            }
        }
    }

    private static void assertLoopback(InetSocketAddress endpoint) throws Exception {
        assertNotNull(endpoint);
        // The API intentionally returns unresolved socket addresses; inspect the numeric literal.
        String address = endpoint.getHostString();
        assertTrue(address.equals("127.0.0.1") || address.equals("::1") ||
                address.equals("0:0:0:0:0:0:0:1"), address);
        assertTrue(InetAddress.getByName(address).isLoopbackAddress());
        assertTrue(endpoint.getPort() > 0);
    }

    private static byte[] checkMessage(ByteBuffer bytes, int expectedIndex, int messages, int bytesPerMessage) {
        assertEquals(bytesPerMessage, bytes.remaining());
        byte[] copy = new byte[bytesPerMessage];
        bytes.duplicate().get(copy);
        int index = ByteBuffer.wrap(copy).order(ByteOrder.BIG_ENDIAN).getInt();
        assertEquals(expectedIndex, index, "Reliable ordered native delivery changed");
        assertTrue(index < messages);
        assertArrayEquals(payload(index, bytesPerMessage), copy);
        return copy;
    }

    private static byte[] payload(int index, int bytesPerMessage) {
        ByteBuffer bytes = ByteBuffer.allocate(bytesPerMessage).order(ByteOrder.BIG_ENDIAN);
        bytes.putInt(index);
        for (int position = 4; position < bytesPerMessage; position++) {
            bytes.put((byte) ((index * 31 + position * 17) ^ 0x5a));
        }
        return bytes.array();
    }

    private static ByteBuffer direct(byte[] bytes) {
        ByteBuffer result = ByteBuffer.allocateDirect(bytes.length);
        result.put(bytes);
        result.flip();
        return result;
    }

    private static void enqueue(ArrayBlockingQueue<Signal> signals, Signal signal,
                                AtomicReference<Throwable> failure) {
        if (!signals.offer(signal)) {
            failure.compareAndSet(null, new AssertionError("Signaling queue exceeded its bound"));
        }
    }

    private static final class Signal {
        private final PeerConnection target;
        private final String data;
        private final SessionDescriptionType type;
        private final String mediaId;

        private Signal(PeerConnection target, String data, SessionDescriptionType type, String mediaId) {
            this.target = target;
            this.data = data;
            this.type = type;
            this.mediaId = mediaId;
        }
    }
}
