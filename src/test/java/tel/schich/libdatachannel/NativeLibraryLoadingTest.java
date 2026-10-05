package tel.schich.libdatachannel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeLibraryLoadingTest {
    @TempDir Path root;

    @Test
    void loadsFromFreshTempAndCleansUpAtExit() throws Exception {
        Path temp = Files.createDirectory(root.resolve("temp"));
        String output = runProbe(temp, root, null, 0);
        assertTrue(output.contains(temp.toRealPath().toString()), output);
        try (var children = Files.list(temp)) {
            assertEquals(0, children.count(), output);
        }
    }

    @Test
    void fallsBackToUserCacheForUnusableTempAndExplicitClasspath() throws Exception {
        Path blocked = Files.write(root.resolve("blocked-temp"), new byte[]{1});
        String output = runProbe(blocked, root, "/native/" + Platform.libraryFilename(LibDataChannel.LIB_NAME), 0);
        assertTrue(output.contains("ngengine" + File.separator + LibDataChannel.LIB_NAME), output);
    }

    @Test
    void fallsBackToNgeWhenTempAndCacheAreUnusable() throws Exception {
        Path blocked = Files.write(root.resolve("blocked-temp"), new byte[]{1});
        for (String cache : List.of("Library", "AppData", ".cache")) {
            Files.write(root.resolve(cache), new byte[]{1});
        }
        String output = runProbe(blocked, root, null, 0);
        assertTrue(output.contains(".nge" + File.separator + LibDataChannel.LIB_NAME), output);
    }

    @Test
    void preservesEveryExtractionFailureWhenNoRootWorks() throws Exception {
        Path blocked = Files.write(root.resolve("blocked"), new byte[]{1});
        String output = runProbe(blocked, blocked, null, 1);
        assertTrue(output.contains("from temp, user cache, or ~/.nge"), output);
        assertEquals(3, output.split("Suppressed:", -1).length - 1, output);
    }

    @Test
    void rejectsMacClassifierWithoutAllocatorBeforeLoadingJni() throws Exception {
        assumeTrue(Platform.isMacOS());
        Path incomplete = root.resolve("incomplete.jar");
        String resource = "native/" + Platform.libraryFilename(LibDataChannel.LIB_NAME);
        try (JarFile original = new JarFile(System.getProperty("native.test.jar"));
             ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(incomplete))) {
            archive.putNextEntry(new ZipEntry(resource));
            try (var input = original.getInputStream(original.getJarEntry(resource))) {
                input.transferTo(archive);
            }
            archive.closeEntry();
        }
        Path temp = Files.createDirectory(root.resolve("temp"));
        String output = runProbe(temp, root, null, 1, incomplete);
        assertTrue(output.contains("libdatachannel_mimalloc.dylib not found"), output);
        try (var children = Files.list(temp)) {
            assertEquals(0, children.count(), output);
        }
    }

    private String runProbe(Path temp, Path home, String classPathOverride, int expectedExit) throws Exception {
        return runProbe(temp, home, classPathOverride, expectedExit, Path.of(System.getProperty("native.test.jar")));
    }

    private String runProbe(Path temp, Path home, String classPathOverride, int expectedExit, Path nativeJar) throws Exception {
        // Gradle workers use their own classloader; construct the child classpath explicitly.
        List<String> classPath = new ArrayList<>();
        classPath.add(Path.of(LibDataChannel.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        classPath.add(Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        classPath.add(nativeJar.toString());
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", Platform.isWindows() ? "java.exe" : "java").toString(),
                "-Djava.io.tmpdir=" + temp, "-Duser.home=" + home));
        if (classPathOverride != null) {
            command.add("-D" + Platform.classPathPropertyNameForLibrary(LibDataChannel.LIB_NAME) + "=" + classPathOverride);
        }
        command.addAll(List.of("-cp", String.join(File.pathSeparator, classPath), Probe.class.getName()));
        Path log = root.resolve("probe.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Native loading probe timed out");
            String output = Files.readString(log);
            assertEquals(expectedExit, process.exitValue(), output);
            if (expectedExit == 0) assertTrue(output.contains("NATIVE_OK"), output);
            return output;
        } finally {
            process.destroyForcibly();
        }
    }

    public static class Probe {
        public static void main(String[] args) {
            Logger logger = Logger.getLogger(Platform.class.getName());
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.FINEST);
            ConsoleHandler handler = new ConsoleHandler();
            handler.setLevel(Level.FINEST);
            logger.addHandler(handler);
            LibDataChannel.initialize();
            try (PeerConnection peer = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT)) {
                System.out.println("NATIVE_OK " + LibDataChannel.getInnerAllocator());
            }
        }
    }
}
