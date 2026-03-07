package tel.schich.libdatachannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

class Platform {
    private static final Logger LOGGER = LoggerFactory.getLogger(Platform.class);

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

    public static boolean isMacOS() {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }

    public static OS getOS() {
        if (isLinux()) {
            return OS.LINUX;
        } else if (isAndroid()) {
            return OS.ANDROID;
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
            LOGGER.trace("Loaded native library {} from library path", name);
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
        if (arch.contains("arm")) {
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
        } else if (arch.contains("aarch64") || arch.contains("arm64")) {
            if (getOS() == OS.MACOS) {
                return "arm64"; // macOS uses arm64 instead of aarch64
            }
            return "aarch64";
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
            LOGGER.trace("Loading native library {} from {}", name, explicitLibraryPath);
            loadSiblingDependencies(name, Path.of(explicitLibraryPath).getParent());
            System.load(explicitLibraryPath);
            return;
        }

        String explicitLibraryClassPath = System.getProperty(classPathPropertyNameForLibrary(name));
        final String libName = libraryFilename(name);
        if (explicitLibraryClassPath != null) {
            LOGGER.trace("Loading native library {} from explicit classpath at {}", name, explicitLibraryClassPath);
            try {
                final Path tempDirectory = Files.createTempDirectory(name + "-");
                final Path libPath = tempDirectory.resolve(libName);
                loadFromClassPath(name, base, explicitLibraryClassPath, tempDirectory, libPath);
                return;
            } catch (IOException e) {
                throw new LinkageError("Unable to load native library " + name + "!", e);
            }
        }

        final String sourceLibPath = LIB_PREFIX + "/" + libName;
        LOGGER.trace("Loading native library {} from {}", name, sourceLibPath);

        try {
            final Path tempDirectory = Files.createTempDirectory(name + "-");
            final Path libPath = tempDirectory.resolve(libName);
            loadFromClassPath(name, base, sourceLibPath, tempDirectory, libPath);
        } catch (IOException e) {
            throw new LinkageError("Unable to load native library " + name + "!", e);
        }
    }

    private static void loadFromClassPath(String name, Class<?> base, String classPath, Path tempDirectory, Path fsPath) throws IOException {
        for (String dependency : dependentLibraryFilenames(name)) {
            final String dependencyClassPath = siblingClassPath(classPath, dependency);
            final Path dependencyPath = tempDirectory.resolve(dependency);
            copyFromClassPath(name, base, dependencyClassPath, dependencyPath, false);
            if (Files.exists(dependencyPath)) {
                System.load(dependencyPath.toString());
                dependencyPath.toFile().deleteOnExit();
            }
        }

        copyFromClassPath(name, base, classPath, fsPath, true);
        System.load(fsPath.toString());
        fsPath.toFile().deleteOnExit();
    }

    private static void loadSiblingDependencies(String name, Path directory) {
        if (directory == null) {
            return;
        }
        for (String dependency : dependentLibraryFilenames(name)) {
            final Path dependencyPath = directory.resolve(dependency);
            if (Files.exists(dependencyPath)) {
                System.load(dependencyPath.toString());
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

            Files.copy(libStream, fsPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public enum OS {
        LINUX,
        WINDOWS,
        ANDROID,
        MACOS,
        UNKNOWN,
    }
}
