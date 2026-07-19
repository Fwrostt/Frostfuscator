package dev.frost.obfuscator.jni.compiler;

/**
 * Supported native CPU architectures.
 */
public enum Architecture {
    X86_64("x86_64"),
    ARM64("arm64");

    private final String resourceName;

    Architecture(String resourceName) {
        this.resourceName = resourceName;
    }

    public String resourceName() {
        return resourceName;
    }

    public static Architecture current() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return ARM64;
        }
        return X86_64;
    }
}


