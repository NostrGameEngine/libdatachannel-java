package tel.schich.libdatachannel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeLibraryExtractionTest {
    @TempDir Path root;

    @Test
    void createsUniquePrivateDirectoriesAndResolvesAliases() throws IOException {
        Path first = NativeLibraryExtraction.createDirectory(root, "datachannel-");
        Path second = NativeLibraryExtraction.createDirectory(root, "datachannel-");
        assertNotEquals(first, second);
        assertEquals(root.toRealPath(), first.getParent());
        if (Files.getFileStore(first).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(first));
            Path alias = root.resolve("alias");
            Files.createSymbolicLink(alias, first);
            Path nested = NativeLibraryExtraction.createDirectory(alias, "datachannel-");
            assertEquals(first, nested.getParent());
        }
    }

    @Test
    void rejectsReplaceableAncestor() throws IOException {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        // macOS java.io.tmpdir already has a private ancestor. Use the shared temp root
        // so another user could actually reach and replace the directory under test.
        Path sharedTemp = Path.of("/tmp");
        assumeTrue(Files.isDirectory(sharedTemp));
        Path unsafe = Files.createTempDirectory(sharedTemp, "datachannel-unsafe-");
        try {
            Files.setPosixFilePermissions(unsafe, PosixFilePermissions.fromString("rwxrwxrwx"));
            assertThrows(IOException.class, () -> NativeLibraryExtraction.createDirectory(unsafe, "datachannel-"));
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
            System.setProperty("os.name", "Mac OS X");
            for (String arch : new String[]{"arm64", "aarch64"}) {
                System.setProperty("os.arch", arch);
                assertEquals("macos-arm64", Platform.detectArch());
            }
        } finally {
            System.setProperty("os.name", previousOs);
            System.setProperty("os.arch", previousArch);
        }
    }
}
