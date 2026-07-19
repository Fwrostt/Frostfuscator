package dev.frost.obfuscator.jni.compiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Prepares generated sources in a predictable native build layout.
 */
public final class NativeSourcePreparer {
    public SourceLayout prepare(Path sourceDirectory, Path buildDirectory) throws IOException {
        Path generated = buildDirectory.resolve("generated");
        Path classes = generated.resolve("classes");
        Path runtime = generated.resolve("runtime");
        Path include = generated.resolve("include");
        Path headers = generated.resolve("headers");
        Files.createDirectories(classes);
        Files.createDirectories(runtime);
        Files.createDirectories(include);
        Files.createDirectories(headers);

        try (var stream = Files.list(sourceDirectory)) {
            for (Path source : stream.filter(path -> path.getFileName().toString().endsWith(".cpp")).toList()) {
                Files.copy(source, classes.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path runtimeHeader = sourceDirectory.resolve("frostjni_runtime.hpp");
        if (Files.isRegularFile(runtimeHeader)) {
            Files.copy(runtimeHeader, include.resolve("frostjni_runtime.hpp"), StandardCopyOption.REPLACE_EXISTING);
        }
        writeSupportSources(generated, runtime, Files.isRegularFile(sourceDirectory.resolve("frostjni_registrar.cpp")));
        return new SourceLayout(generated, classes, runtime, include, headers);
    }

    private void writeSupportSources(Path generated, Path runtime, boolean hasRegistrar) throws IOException {
        if (!hasRegistrar) {
            Files.writeString(generated.resolve("bootstrap.cpp"), """
                    #include <jni.h>

                    extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
                        return JNI_VERSION_1_8;
                    }
                    """);
        }
        Files.writeString(runtime.resolve("frost_runtime.cpp"), """
                #include "frostjni_runtime.hpp"

                namespace frostjni {
                void frostRuntimeAnchor() {}
                }
                """);
        Files.writeString(runtime.resolve("frost_cache.cpp"), """
                #include "frostjni_runtime.hpp"

                namespace frostjni {
                void frostCacheAnchor() {}
                }
                """);
    }
}


