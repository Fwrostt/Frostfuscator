package dev.frost.obfuscator.jni.compiler;

/**
 * Native target platform metadata.
 */
public record TargetPlatform(OperatingSystem operatingSystem, Architecture architecture) {
    public static TargetPlatform current() {
        return new TargetPlatform(OperatingSystem.current(), Architecture.current());
    }
}


