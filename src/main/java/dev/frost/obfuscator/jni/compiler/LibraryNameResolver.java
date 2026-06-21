package dev.frost.obfuscator.jni.compiler;

/**
 * Resolves platform-native library names.
 */
public final class LibraryNameResolver {
    public String libraryName(String baseName, TargetPlatform targetPlatform) {
        return switch (targetPlatform.operatingSystem()) {
            case WINDOWS -> baseName + ".dll";
            case LINUX -> "lib" + baseName + ".so";
            case MACOS -> "lib" + baseName + ".dylib";
        };
    }
}


