package dev.frost.obfuscator.jni.compiler;

/**
 * Supported native operating systems.
 */
public enum OperatingSystem {
    WINDOWS("windows", "dll"),
    LINUX("linux", "so"),
    MACOS("macos", "dylib");

    private final String resourceName;
    private final String libraryExtension;

    OperatingSystem(String resourceName, String libraryExtension) {
        this.resourceName = resourceName;
        this.libraryExtension = libraryExtension;
    }

    public String resourceName() {
        return resourceName;
    }

    public String libraryExtension() {
        return libraryExtension;
    }

    public static OperatingSystem current() {
        String name = System.getProperty("os.name", "").toLowerCase();
        if (name.contains("win")) {
            return WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return MACOS;
        }
        return LINUX;
    }
}


