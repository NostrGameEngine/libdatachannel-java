package tel.schich.libdatachannel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jme3.nativebootstrap.common.OperatingSystem;
import com.jme3.nativebootstrap.directories.NativeDirectories;
import com.jme3.nativebootstrap.loader.NativeLoader;
import com.jme3.nativebootstrap.os.OperatingSystems;

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
        Path cache;
        switch (Platform.getOS()) {
            case WINDOWS: cache = root.resolve("AppData/Local"); break;
            case MACOS: cache = root.resolve("Library/Caches"); break;
            default: cache = root.resolve(".cache");
        }
        assertTrue(output.contains(cache.resolve(LibDataChannel.LIB_NAME).toRealPath().toString()), output);
    }

    @Test
    void fallsBackToBootstrapHomeWhenTempAndCacheAreUnusable() throws Exception {
        Path blocked = Files.write(root.resolve("blocked-temp"), new byte[]{1});
        for (String cache : List.of("Library", "AppData", ".cache")) {
            Files.write(root.resolve(cache), new byte[]{1});
        }
        String output = runProbe(blocked, root, null, 0);
        assertTrue(output.contains(root.resolve(".jme3/natives").resolve(LibDataChannel.LIB_NAME).toRealPath().toString()), output);
    }

    @Test
    void honorsNativeBootstrapOverridesWithTheRebrandedLoader() throws Exception {
        Path blocked = Files.write(root.resolve("blocked"), new byte[]{1});
        Path home = root.resolve("override-home");
        String output = runProbe(blocked, blocked, null, 0, Path.of(System.getProperty("native.test.jar")),
                "-Dnatives.tempDir=" + blocked, "-Dnatives.cacheDir=" + blocked,
                "-Dnatives.userHome=" + home, "-Dnatives.namespace=.custom");
        assertTrue(output.contains(home.resolve(".custom/natives").resolve(LibDataChannel.LIB_NAME).toRealPath().toString()), output);
    }

    @Test
    void preservesEveryExtractionFailureWhenNoRootWorks() throws Exception {
        Path blocked = Files.write(root.resolve("blocked"), new byte[]{1});
        String output = runProbe(blocked, blocked, null, 1);
        assertTrue(output.contains("Native loading failed"), output);
        assertEquals(4, output.split("Suppressed:", -1).length - 1, output);
    }

    @Test
    void loadsWithoutMountMetadataOrUserNameLookup() throws Exception {
        Path temp = Files.createDirectory(root.resolve("temp"));
        String output = runProbe(temp, root, null, 0, Path.of(System.getProperty("native.test.jar")),
                "-Djava.security.manager=allow", "-Dnative.test.deny-filestore=true", "-Duser.name=?");
        assertTrue(output.contains(temp.toRealPath().toString()), output);
    }

    @Test
    void loadsFromArchitecturePrefixedResources() throws Exception {
        String prefix = Platform.detectArch() + "/";
        Path nativeJar = nativeJarWithPrefix(prefix);
        Path temp = Files.createDirectory(root.resolve("temp"));
        runProbe(temp, root, "/" + prefix + "native/" + Platform.libraryFilename(LibDataChannel.LIB_NAME), 0, nativeJar);
    }

    @Test
    void preservesRelativeClasspathOverrides() throws Exception {
        Path nativeJar = nativeJarWithPrefix("tel/schich/libdatachannel/fixtures/");
        Path temp = Files.createDirectory(root.resolve("temp"));
        runProbe(temp, root, "fixtures/native/" + Platform.libraryFilename(LibDataChannel.LIB_NAME), 0, nativeJar);
    }

    private Path nativeJarWithPrefix(String prefix) throws Exception {
        Path jar = root.resolve("prefixed.jar");
        try (JarFile original = new JarFile(System.getProperty("native.test.jar"));
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
            var entries = original.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith("native/")) continue;
                output.putNextEntry(new ZipEntry(prefix + entry.getName()));
                try (var input = original.getInputStream(entry)) { input.transferTo(output); }
                output.closeEntry();
            }
        }
        return jar;
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
        assertTrue(output.contains("datachannel_mimalloc"), output);
        try (var children = Files.list(temp)) {
            assertEquals(0, children.count(), output);
        }
    }

    @Test
    void fallsBackToSystemLibrariesWhenClasspathResourcesAreAbsent() throws Exception {
        Path blocked = Files.write(root.resolve("blocked"), new byte[]{1});
        Path system = systemLibraries();
        String output = runProbe(blocked, blocked, null, 0, null, "-Djava.library.path=" + system);
        assertTrue(output.contains("from library path"), output);
    }

    @Test
    void prefersBundledLibrariesByDefaultAndHonorsOsPreference() throws Exception {
        Path temp = Files.createDirectory(root.resolve("temp"));
        Path system = systemLibraries();
        Path jar = Path.of(System.getProperty("native.test.jar"));
        String output = runProbe(temp, root, null, 0, jar, "-Djava.library.path=" + system);
        assertTrue(output.contains(temp.toRealPath().toString()), output);
        output = runProbe(temp, root, null, 0, jar, "-Djava.library.path=" + system,
                "-Dnatives.preferOsLibraries=true");
        assertTrue(output.contains("from library path"), output);
        output = runProbe(temp, root, null, 0, jar, "-Djava.library.path=" + system,
                "-Dnatives.preferOsLibraries=true", "-Dnatives.withOsLibraries=false");
        assertTrue(output.contains(temp.toRealPath().toString()), output);
    }

    @Test
    void disablingOsLibrariesRejectsMissingClasspathResources() throws Exception {
        Path temp = Files.createDirectory(root.resolve("temp"));
        Path system = systemLibraries();
        String output = runProbe(temp, root, null, 1, null, "-Djava.library.path=" + system,
                "-Dnatives.withOsLibraries=false", "-Dnatives.preferOsLibraries=true");
        assertTrue(output.contains("Native resource not found"), output);
    }

    @Test
    void preservesExplicitNativePathAndSiblingAllocator() throws Exception {
        Path blocked = Files.write(root.resolve("blocked"), new byte[]{1});
        Path system = systemLibraries();
        runProbe(blocked, blocked, null, 0, null,
                "-Dlibdatachannel.native.datachannel-java.path=" + system.resolve(Platform.libraryFilename(LibDataChannel.LIB_NAME)),
                "-Dnatives.withOsLibraries=false");
    }

    @Test
    void androidUsesSystemLibrariesWithoutExtractingBundledResources() throws Exception {
        Path temp = Files.createDirectory(root.resolve("temp"));
        Path system = systemLibraries();
        Path jar = Path.of(System.getProperty("native.test.jar"));
        runProbe(temp, root, null, 0, jar, "-Djava.library.path=" + system,
                "-Dnative.test.android=true");
        String output = runProbe(temp, root, null, 1, jar, "-Dnative.test.android=true");
        assertTrue(output.contains("UnsatisfiedLinkError"), output);
        try (var children = Files.list(temp)) {
            assertEquals(0, children.count(), "Android must use the installed native library");
        }
    }

    private Path systemLibraries() throws Exception {
        Path directory = Files.createDirectory(root.resolve("system-libraries"));
        try (JarFile jar = new JarFile(System.getProperty("native.test.jar"))) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith("native/")) continue;
                String fileName = entry.getName().substring("native/".length());
                Path file = directory.resolve(fileName);
                try (var input = jar.getInputStream(entry)) { Files.copy(input, file); }
                if (Platform.isWindows() && fileName.startsWith("lib")) {
                    Files.copy(file, directory.resolve(fileName.substring(3)));
                }
            }
        }
        return directory;
    }

    private String runProbe(Path temp, Path home, String classPathOverride, int expectedExit) throws Exception {
        return runProbe(temp, home, classPathOverride, expectedExit, Path.of(System.getProperty("native.test.jar")));
    }

    private String runProbe(Path temp, Path home, String classPathOverride, int expectedExit, Path nativeJar, String... vmOptions) throws Exception {
        // Gradle workers use their own classloader; construct the child classpath explicitly.
        List<String> classPath = new ArrayList<>();
        for (Class<?> type : List.of(LibDataChannel.class, Probe.class, NativeLoader.class, NativeDirectories.class, OperatingSystem.class, OperatingSystems.class)) {
            classPath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        if (nativeJar != null) classPath.add(nativeJar.toString());
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", Platform.isWindows() ? "java.exe" : "java").toString(),
                "-Djava.io.tmpdir=" + temp, "-Duser.home=" + home,
                "-Djava.library.path=" + root.resolve("no-system-natives")));
        command.addAll(List.of(vmOptions));
        if (classPathOverride != null) {
            command.add("-D" + Platform.classPathPropertyNameForLibrary(LibDataChannel.LIB_NAME) + "=" + classPathOverride);
        }
        command.addAll(List.of("-cp", String.join(File.pathSeparator, classPath), Probe.class.getName()));
        Path log = root.resolve("probe.log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().remove("XDG_CACHE_HOME");
        builder.environment().remove("LOCALAPPDATA");
        Process process = builder.start();
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
            if (Boolean.getBoolean("native.test.android")) {
                System.setProperty("java.runtime.name", "Android Runtime");
                System.setProperty("java.vm.name", "Dalvik");
            }
            if (Boolean.getBoolean("native.test.deny-filestore")) {
                System.setSecurityManager(new SecurityManager() {
                    @Override public void checkPermission(java.security.Permission permission) {
                        if (permission.getName().equals("getFileStoreAttributes")) {
                            throw new SecurityException("Mount point not found");
                        }
                    }
                });
            }
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
