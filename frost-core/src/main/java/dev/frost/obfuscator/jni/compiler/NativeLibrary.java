package dev.frost.obfuscator.jni.compiler;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Native library produced by a compiler backend.
 */
public record NativeLibrary(String libraryName, TargetPlatform targetPlatform, Path path) {
    public NativeLibrary {
        Objects.requireNonNull(libraryName, "libraryName");
        Objects.requireNonNull(targetPlatform, "targetPlatform");
        Objects.requireNonNull(path, "path");
    }

    public String resourcePath() {
        return "native/"
                + targetPlatform.operatingSystem().resourceName()
                + "/"
                + targetPlatform.architecture().resourceName()
                + "/"
                + libraryName;
    }
}


