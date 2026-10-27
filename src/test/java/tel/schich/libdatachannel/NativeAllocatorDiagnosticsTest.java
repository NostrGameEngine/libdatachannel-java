package tel.schich.libdatachannel;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs only in the dedicated diagnostic task, using disposable JVMs so that
 * the deliberately injected foreign pointer cannot taint the positive run.
 */
class NativeAllocatorDiagnosticsTest {
    private static native long[] snapshot();
    private static native void checkForeignBuffer(ByteBuffer buffer);
    private static native void finish();

    @Test
    void auditsRealTransfersAndRejectsForeignMemory() throws Exception {
        Path reports = Path.of(System.getProperty("native.test.allocator.reports"));
        Files.createDirectories(reports);
        runChild("positive", reports, 0);
        runChild("negative", reports, 1);
        assertTrue(Files.readString(reports.resolve("positive.jsonl")).contains("\"phase\":\"cleanup\""));
        assertTrue(Files.readString(reports.resolve("negative.jsonl")).contains("\"violations\":1"));
        assertTrue(Files.readString(reports.resolve("negative.log")).contains("FOREIGN_POINTER_REJECTED"));
    }

    private static void runChild(String mode, Path reports, int expectedExit) throws Exception {
        String executable = Platform.isWindows() ? "java.exe" : "java";
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Dlibdatachannel.native.datachannel-java.path=" + System.getProperty("native.test.library.path"),
                "-Dlibdatachannel.native.tests=true", "-cp", System.getProperty("native.test.classpath"),
                NativeAllocatorDiagnosticsTest.class.getName(), mode, reports.resolve(mode + ".jsonl").toString());
        Path log = reports.resolve(mode + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(240, TimeUnit.SECONDS), "Diagnostic JVM timed out: " + log);
            assertEquals(expectedExit, process.exitValue(), () -> {
                try { return Files.readString(log); }
                catch (Exception failure) { return failure.toString(); }
            });
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    public static void main(String[] arguments) throws Exception {
        LibDataChannel.initialize();
        assertTrue(LibDataChannel.getInnerAllocator().startsWith("mimalloc-secure+"));
        try (PrintWriter report = new PrintWriter(new File(arguments[1]))) {
            long[] baseline = snapshot();
            assertTrue(baseline[0] > 0, "Actual allocator hooks must run during initialization");
            record(report, "initialization", new long[5], baseline);
            if (arguments[0].equals("negative")) {
                checkForeignBuffer(ByteBuffer.allocateDirect(8192));
                long[] rejected = snapshot();
                record(report, "foreign-pointer", baseline, rejected);
                assertEquals(1, rejected[3]);
                System.err.println("FOREIGN_POINTER_REJECTED");
                throw new AssertionError("Foreign allocation must fail the zero-violations rule");
            }
            assertEquals(0, baseline[3]);
            phase(report, "ownership-probes", () -> new NativeAllocatorTest()
                    .routesDesktopNativeAllocationsThroughThePrivateSecureAllocator());
            for (int round = 0; round < 3; ++round) {
                phase(report, "small-" + round, () -> transfer(256, 64));
                phase(report, "medium-" + round, () -> transfer(128, 4096));
                phase(report, "fragmented-" + round, () -> transfer(16, 65536));
            }
            phase(report, "concurrent-peers", () -> {
                ExecutorService executor = Executors.newFixedThreadPool(4);
                try {
                    List<Future<?>> transfers = new ArrayList<>();
                    for (int index = 0; index < 4; ++index) {
                        transfers.add(executor.submit(() -> {
                            try { transfer(64, 16384); }
                            catch (Exception failure) { throw new RuntimeException(failure); }
                        }));
                    }
                    for (Future<?> transfer : transfers) transfer.get(60, TimeUnit.SECONDS);
                } finally {
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
                }
            });
            long[] before = snapshot();
            finish();
            long[] after = snapshot();
            record(report, "cleanup", before, after);
            record(report, "total", new long[5], after);
            assertEquals(0, after[3], "Foreign pointer observed through native cleanup");
            System.out.println("ALLOCATOR_DIAGNOSTICS_OK");
        }
    }

    private static void transfer(int messages, int bytes) throws Exception {
        PeerConnectionBindAddressTest.transferOrderedBytesBothWays(messages, bytes);
    }

    private static void phase(PrintWriter report, String name, Workload workload) throws Exception {
        long[] before = snapshot();
        workload.run();
        long[] after = snapshot();
        record(report, name, before, after);
        assertTrue(after[0] > before[0], name + " did not exercise actual Mi allocations");
        assertTrue(after[1] > before[1], name + " did not exercise actual Mi frees");
        assertEquals(0, after[3], name + " observed foreign native memory");
    }

    private static void record(PrintWriter report, String phase, long[] before, long[] after) {
        report.printf("{\"phase\":\"%s\",\"allocations\":%d,\"frees\":%d,\"requestedBytes\":%d,\"violations\":%d,\"alignmentAdjustments\":%d}%n",
                phase, after[0] - before[0], after[1] - before[1], after[2] - before[2], after[3], after[4] - before[4]);
        report.flush();
    }

    @FunctionalInterface
    private interface Workload {
        void run() throws Exception;
    }
}
