package tel.schich.libdatachannel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.DirectoryStream;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

class Platform {
    private static final Logger LOGGER = Logger.getLogger(Platform.class.getName());

    private static final String LIB_PREFIX = "/native";
    private static final String PATH_PROP_PREFIX = "libdatachannel.native.";
    private static final String PATH_PROP_FS_PATH = ".path";
    private static final String PATH_PROP_CLASS_PATH = ".classpath";

    /**
     * Checks if the currently running OS is Linux
     *
     * @return true if running on Linux
     */
    public static boolean isLinux() {
        return System.getProperty("os.name").equalsIgnoreCase("Linux");
    }

    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    public static boolean isAndroid() {
        try {
            return System.getProperty("java.specification.vendor").contains("Android") ||
                    System.getProperty("java.vendor").contains("Android") ||
                    System.getProperty("java.vm.vendor").contains("Android");
        } catch (SecurityException e) {
            return System.getProperty("java.vm.name").toLowerCase().contains("dalvik") ||
                    System.getProperty("java.vm.name").toLowerCase().contains("art");
        }
    }

    public static boolean isIOS() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        return Boolean.getBoolean("libdatachannel.ios") ||
                osName.equals("ios") ||
                osName.contains("iphone") ||
                osName.contains("ipad");
    }

    public static boolean isMacOS() {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }

    public static OS getOS() {
        if (isLinux()) {
            return OS.LINUX;
        } else if (isAndroid()) {
            return OS.ANDROID;
        } else if (isIOS()) {
            return OS.IOS;
        } else if (isMacOS()) {
            return OS.MACOS;
        } else if (isWindows()) {
            return OS.WINDOWS;
        } else {
            return OS.UNKNOWN;
        }
    }


    public static void loadNativeLibrary(String name, Class<?> base) {
        try {
            System.loadLibrary(name);
            LOGGER.log(Level.FINEST, "Loaded native library {0} from library path", name);
        } catch (LinkageError e) {
            loadExplicitLibrary(name, base);
        }
    }

    public static String classPathPropertyNameForLibrary(String name) {
        return PATH_PROP_PREFIX + name.toLowerCase() + PATH_PROP_CLASS_PATH;
    }

    private static String archPrefixForOs() {
        if (getOS() == OS.WINDOWS) {
            return "windows-";
        }
        if (getOS() == OS.ANDROID) {
            return "android-";
        }
        if (getOS() == OS.MACOS) {
            return "macos-";
        }
        return "";
    }

    private static String detectCpuArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return getOS() == OS.MACOS ? "arm64" : "aarch64";
        } else if (arch.contains("arm")) {
            return "armv7";
        } else if (arch.contains("86") || arch.contains("amd")) {
            if (arch.contains("64")) {
                return "x86_64";
            }
            return "x86_32";
        } else if (arch.contains("riscv")) {
            if (arch.contains("64")) {
                return "riscv64";
            }
            return "riscv32";
        }
        return arch;
    }

    public static String detectArch() {
        return archPrefixForOs() + detectCpuArch();
    }

    public static String libraryFilename(String name) {
        final String libName = "lib" + name;
        if (getOS() == OS.WINDOWS) {
            return libName + ".dll";
        } else if (getOS() == OS.MACOS) {
            return libName + ".dylib";
        }
        return libName + ".so";
    }

    private static List<String> dependentLibraryFilenames(String name) {
        if (!LibDataChannel.LIB_NAME.equals(name)) {
            return List.of();
        }
        switch (getOS()) {
            case WINDOWS:
                return List.of("libdatachannel_mimalloc.dll");
            case MACOS:
                return List.of("libdatachannel_mimalloc.dylib");
            case LINUX:
                return List.of("libdatachannel_mimalloc.so");
            default:
                return List.of();
        }
    }

    private static void loadExplicitLibrary(String name, Class<?> base) {
        String explicitLibraryPath = System.getProperty(PATH_PROP_PREFIX + name.toLowerCase() + PATH_PROP_FS_PATH);
        if (explicitLibraryPath != null) {
            LOGGER.log(Level.FINEST, "Loading native library {0} from {1}", new Object[]{name, explicitLibraryPath});
            loadSiblingDependencies(name, Path.of(explicitLibraryPath).getParent());
            System.load(explicitLibraryPath);
            return;
        }

        String explicitLibraryClassPath = System.getProperty(classPathPropertyNameForLibrary(name));
        final String libName = libraryFilename(name);
        final String sourceLibPath = explicitLibraryClassPath != null
                ? explicitLibraryClassPath : LIB_PREFIX + "/" + libName;
        LOGGER.log(Level.FINEST, "Loading native library {0} from {1}", new Object[]{name, sourceLibPath});
        loadFromExtractionRoots(name, base, sourceLibPath, libName);
    }

    private static void loadFromExtractionRoots(String name, Class<?> base, String classPath, String libName) {
        UnsatisfiedLinkError error = new UnsatisfiedLinkError(
                "Unable to extract/load native library " + name + " from temp, user cache, or ~/.nge.");
        for (int index = 0; index < 3; index++) {
            Path directory = null;
            boolean loaded = false;
            try {
                Path root = extractionRoot(index, name);
                if (root == null) {
                    continue;
                }
                directory = NativeLibraryExtraction.createDirectory(root, name + "-");
                loadFromClassPath(name, base, classPath, directory, directory.resolve(libName));
                loaded = true;
                LOGGER.log(Level.FINEST, "Loaded native library {0} from {1}", new Object[]{name, directory});
                return;
            } catch (IOException | UnsatisfiedLinkError | SecurityException | IllegalArgumentException
                     | UnsupportedOperationException failure) {
                error.addSuppressed(failure);
            } finally {
                if (!loaded && directory != null) {
                    cleanupExtraction(directory, error);
                }
            }
        }
        throw error;
    }

    static Path extractionRoot(int index, String name) {
        if (index == 0) {
            String tmp = System.getProperty("java.io.tmpdir", "").trim();
            return tmp.isEmpty() ? null : Path.of(tmp);
        }
        String home = System.getProperty("user.home", "").trim();
        if (home.isEmpty()) {
            return null;
        }
        Path userHome = Path.of(home);
        if (index == 1) {
            Path cache;
            switch (getOS()) {
                case WINDOWS:
                    cache = userHome.resolve("AppData/Local");
                    break;
                case MACOS:
                    cache = userHome.resolve("Library/Caches");
                    break;
                default:
                    cache = userHome.resolve(".cache");
                    break;
            }
            return cache.resolve("ngengine").resolve(name);
        }
        return userHome.resolve(".nge").resolve(name);
    }

    private static void cleanupExtraction(Path directory, Throwable failure) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        } catch (IOException | SecurityException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException | SecurityException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void loadFromClassPath(String name, Class<?> base, String classPath, Path tempDirectory, Path fsPath) throws IOException {
        for (String dependency : dependentLibraryFilenames(name)) {
            final String dependencyClassPath = siblingClassPath(classPath, dependency);
            copyFromClassPath(name, base, dependencyClassPath, tempDirectory.resolve(dependency), isMacOS());
        }
        copyFromClassPath(name, base, classPath, fsPath, true);
        loadSiblingDependencies(name, tempDirectory);
        System.load(fsPath.toAbsolutePath().toString());
    }

    private static void loadSiblingDependencies(String name, Path directory) {
        if (directory == null) {
            return;
        }
        for (String dependency : dependentLibraryFilenames(name)) {
            final Path dependencyPath = directory.resolve(dependency);
            if (Files.exists(dependencyPath)) {
                System.load(dependencyPath.toAbsolutePath().toString());
            }
        }
    }

    private static String siblingClassPath(String classPath, String fileName) {
        final int slashIndex = classPath.lastIndexOf('/');
        if (slashIndex < 0) {
            return fileName;
        }
        return classPath.substring(0, slashIndex + 1) + fileName;
    }

    private static void copyFromClassPath(String name, Class<?> base, String classPath, Path fsPath, boolean required) throws IOException {
        try (InputStream libStream = base.getResourceAsStream(classPath)) {
            if (libStream == null) {
                if (required) {
                    throw new LinkageError("Failed to load the native library " + name + ": " + classPath + " not found.");
                }
                return;
            }

            Files.copy(libStream, fsPath);
            fsPath.toFile().deleteOnExit();
        }
    }

    public enum OS {
        LINUX,
        WINDOWS,
        ANDROID,
        IOS,
        MACOS,
        UNKNOWN,
    }
}
