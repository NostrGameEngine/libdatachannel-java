package tel.schich.libdatachannel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jme3.nativebootstrap.common.OperatingSystem;
import com.jme3.nativebootstrap.directories.NativeDirectories;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeDirectoryIntegrationTest {
    @TempDir Path root;

    private Path createDirectory(Path directory) {
        return NativeDirectories.fromRoots("datachannel", List.of(directory), Platform::getOS).get(0).get();
    }

    @Test
    void createsUniquePrivateDirectoriesAndResolvesAliases() throws IOException {
        Path first = createDirectory(root);
        Path second = createDirectory(root);
        assertNotEquals(first, second);
        assertEquals(root.toRealPath(), first.getParent());
        if (Files.getFileAttributeView(first, PosixFileAttributeView.class) != null) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(first));
            Path alias = root.resolve("alias");
            Files.createSymbolicLink(alias, first);
            Path nested = createDirectory(alias);
            assertEquals(first, nested.getParent());
        }
    }

    @Test
    void rejectsReplaceableAncestor() throws IOException {
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
        // macOS java.io.tmpdir already has a private ancestor. Use the shared temp root
        // so another user could actually reach and replace the directory under test.
        Path sharedTemp = Path.of("/tmp");
        assumeTrue(Files.isDirectory(sharedTemp));
        Path unsafe = Files.createTempDirectory(sharedTemp, "datachannel-unsafe-");
        try {
            Files.setPosixFilePermissions(unsafe, PosixFilePermissions.fromString("rwxrwxrwx"));
            assertThrows(UncheckedIOException.class, () -> createDirectory(unsafe));
        } finally {
            try (var children = Files.list(unsafe)) {
                for (Path child : children.toArray(Path[]::new)) Files.deleteIfExists(child);
            }
            Files.deleteIfExists(unsafe);
        }
    }

    @Test
    void recognizesBothMacArm64PropertySpellings() {
        String previousOs = System.getProperty("os.name");
        String previousArch = System.getProperty("os.arch");
        try {
            for (String os : new String[]{"Mac OS X", "Darwin"}) {
                System.setProperty("os.name", os);
                assertEquals(OperatingSystem.MACOS, Platform.getOS());
                assertFalse(Platform.isWindows());
                for (String arch : new String[]{"arm64", "aarch64"}) {
                    System.setProperty("os.arch", arch);
                    assertEquals("macos-arm64", Platform.detectArch());
                }
            }
        } finally {
            System.setProperty("os.name", previousOs);
            System.setProperty("os.arch", previousArch);
        }
    }

    @Test
    void mapsAndroidBeforeItsLinuxHostLabel() {
        String os = System.getProperty("os.name");
        String runtime = System.getProperty("java.runtime.name");
        try {
            System.setProperty("os.name", "Linux");
            System.setProperty("java.runtime.name", "Android Runtime");
            assertEquals(OperatingSystem.ANDROID, Platform.getOS());
            assertTrue(Platform.detectArch().startsWith("android-"));
        } finally {
            System.setProperty("os.name", os);
            System.setProperty("java.runtime.name", runtime);
        }
    }

    @Test
    void preservesIosStaticLinkOverride() {
        String previous = System.getProperty("libdatachannel.ios");
        try {
            System.setProperty("libdatachannel.ios", "true");
            assertTrue(Platform.isIOS());
            assertEquals(OperatingSystem.IOS, Platform.getOS());
        } finally {
            if (previous == null) System.clearProperty("libdatachannel.ios");
            else System.setProperty("libdatachannel.ios", previous);
        }
    }

    @Test
    void mapsWindowsClassifierNamesToLogicalSystemNames() {
        String previous = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            assertEquals("libdatachannel-java.dll", Platform.libraryFilename(LibDataChannel.LIB_NAME));
            assertEquals("datachannel-java", Platform.systemLibraryName("libdatachannel-java"));
            assertEquals("datachannel_mimalloc", Platform.systemLibraryName("libdatachannel_mimalloc"));
            System.setProperty("os.name", "Darwin");
            assertEquals("datachannel-java", Platform.systemLibraryName("datachannel-java"));
        } finally {
            System.setProperty("os.name", previous);
        }
    }
}
