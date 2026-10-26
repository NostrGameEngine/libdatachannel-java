package tel.schich.libdatachannel;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NativeThreadAttachmentTest {
    private static final ConcurrentLinkedQueue<Thread> CAPTURED = new ConcurrentLinkedQueue<>();

    @BeforeAll
    static void loadProbe() {
        LibDataChannel.initialize();
        System.load(System.getProperty("native.test.probe.path"));
    }

    private static void captureCurrent() {
        CAPTURED.add(Thread.currentThread());
    }

    private static native int capture(String library, int count);

    private static native boolean alreadyAttached(String library);

    @Test
    void nativeThreadsDetachBeforeTheirPhysicalReturnCompletes() {
        CAPTURED.clear();
        assertEquals(32, capture(System.getProperty("native.test.library.path"), 32));
        assertEquals(32, CAPTURED.size());
        Set<Long> identities = new HashSet<>();
        for (Thread thread : CAPTURED) {
            assertTrue(identities.add(thread.getId()));
            assertTrue(thread.isDaemon());
            assertFalse(thread.isAlive(), "Returned native callback must release its Java thread");
            assertEquals(Thread.State.TERMINATED, thread.getState());
        }
    }

    @Test
    void javaOwnedAttachmentsRemainWithTheirOriginalThreads() throws InterruptedException {
        String library = System.getProperty("native.test.library.path");
        Thread caller = Thread.currentThread();
        assertTrue(alreadyAttached(library));
        assertSame(caller, Thread.currentThread());
        assertTrue(caller.isAlive());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                assertTrue(alreadyAttached(library));
                assertTrue(Thread.currentThread().isAlive());
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });
        worker.start();
        worker.join(10_000);
        assertFalse(worker.isAlive(), "Java-owned callback timed out");
        assertNull(failure.get());
    }
}
