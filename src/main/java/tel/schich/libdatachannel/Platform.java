package tel.schich.libdatachannel;

import com.jme3.nativebootstrap.common.OperatingSystem;
import com.jme3.nativebootstrap.directories.NativeDirectories;
import com.jme3.nativebootstrap.loader.NativeLoader;
import com.jme3.nativebootstrap.loader.NativeResource;
import com.jme3.nativebootstrap.os.OperatingSystems;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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
        return getOS() == OperatingSystem.LINUX;
    }

    public static boolean isWindows() {
        return getOS() == OperatingSystem.WINDOWS;
    }

    public static boolean isAndroid() {
        return getOS() == OperatingSystem.ANDROID;
    }

    public static boolean isIOS() {
        return getOS() == OperatingSystem.IOS;
    }

    public static boolean isMacOS() {
        return getOS() == OperatingSystem.MACOS;
    }

    public static OperatingSystem getOS() {
        return Boolean.getBoolean("libdatachannel.ios") ? OperatingSystem.IOS : OperatingSystems.detect();
    }


    public static void loadNativeLibrary(String name, Class<?> base) {
        String explicitLibraryPath = System.getProperty(PATH_PROP_PREFIX + name.toLowerCase(Locale.ROOT) + PATH_PROP_FS_PATH);
        if (explicitLibraryPath != null) {
            LOGGER.log(Level.FINEST, "Loading native library {0} from {1}", new Object[]{name, explicitLibraryPath});
            System.load(explicitLibraryPath);
            return;
        }

        // Android libraries are installed from the AAR's jni entries.
        if (isAndroid()) {
            System.loadLibrary(name);
            return;
        }

        String explicitLibraryClassPath = System.getProperty(classPathPropertyNameForLibrary(name));
        final String libName = libraryFilename(name);
        final String sourceLibPath = explicitLibraryClassPath != null
                ? explicitLibraryClassPath : LIB_PREFIX + "/" + libName;
        LOGGER.log(Level.FINEST, "Loading native library {0} from {1}", new Object[]{name, sourceLibPath});
        loadFromExtractionRoots(name, base, sourceLibPath);
    }

    public static String classPathPropertyNameForLibrary(String name) {
        return PATH_PROP_PREFIX + name.toLowerCase(Locale.ROOT) + PATH_PROP_CLASS_PATH;
    }

    private static String archPrefixForOs() {
        switch (getOS()) {
            case WINDOWS: return "windows-";
            case ANDROID: return "android-";
            case MACOS: return "macos-";
            default: return "";
        }
    }

    private static String detectCpuArch() {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return getOS() == OperatingSystem.MACOS ? "arm64" : "aarch64";
        } else if (arch.contains("arm")) {
            return "armv7";
        } else if (arch.contains("86") || arch.contains("amd")) {
            return arch.contains("64") ? "x86_64" : "x86_32";
        } else if (arch.contains("riscv")) {
            return arch.contains("64") ? "riscv64" : "riscv32";
        }
        return arch;
    }

    public static String detectArch() {
        return archPrefixForOs() + detectCpuArch();
    }

    public static String libraryFilename(String name) {
        final String libName = "lib" + name;
        if (getOS() == OperatingSystem.WINDOWS) {
            return libName + ".dll";
        } else if (getOS() == OperatingSystem.MACOS) {
            return libName + ".dylib";
        }
        return libName + ".so";
    }

    private static void loadFromExtractionRoots(String name, Class<?> base, String classPath) {
        String source = absoluteClassPath(base, classPath);
        Path directory = new NativeLoader(path -> System.load(path.toString()),
                library -> System.loadLibrary(systemLibraryName(library))).load(
                NativeDirectories.candidates(name, Platform::getOS),
                List.of(NativeResource.fromClasspath(base, source)));
        if (directory == null) {
            LOGGER.log(Level.FINEST, "Loaded native library {0} from library path", name);
        } else {
            LOGGER.log(Level.FINEST, "Loaded native library {0} from {1}", new Object[]{name, directory});
        }
    }

    private static String absoluteClassPath(Class<?> base, String classPath) {
        if (classPath.startsWith("/")) return classPath;
        String packagePath = base.getPackageName().replace('.', '/');
        return "/" + (packagePath.isEmpty() ? "" : packagePath + "/") + classPath;
    }

    static String systemLibraryName(String name) {
        OperatingSystem os = getOS();
        // Keep the JNI library's existing logical Windows name.
        return os == OperatingSystem.WINDOWS && name.startsWith("lib") ? name.substring(3) : name;
    }

}
