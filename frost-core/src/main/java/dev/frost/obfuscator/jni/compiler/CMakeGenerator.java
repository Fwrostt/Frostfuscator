package dev.frost.obfuscator.jni.compiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Emits a CMake project for generated FrostJNI native sources.
 */
public final class CMakeGenerator {
    public Path generate(SourceLayout layout, String libraryBaseName, List<Path> sources, List<Path> includePaths) throws IOException {
        Path cmakeLists = layout.root().resolve("CMakeLists.txt");
        StringBuilder builder = new StringBuilder();
        builder.append("cmake_minimum_required(VERSION 3.20)\n");
        builder.append("project(FrostJNIProtected LANGUAGES CXX)\n");
        builder.append("set(CMAKE_CXX_STANDARD 17)\n");
        builder.append("set(CMAKE_CXX_STANDARD_REQUIRED ON)\n");
        builder.append("add_library(").append(libraryBaseName).append(" SHARED\n");
        for (Path source : sources) {
            builder.append("    ").append(cmakePath(layout.root().relativize(source))).append('\n');
        }
        builder.append(")\n");
        builder.append("target_include_directories(").append(libraryBaseName).append(" PRIVATE\n");
        builder.append("    ").append(cmakePath(layout.root().relativize(layout.includeDirectory()))).append('\n');
        builder.append("    ").append(cmakePath(layout.root().relativize(layout.headersDirectory()))).append('\n');
        for (Path includePath : includePaths) {
            builder.append("    ").append(cmakePath(includePath.toAbsolutePath())).append('\n');
        }
        builder.append(")\n");
        Files.writeString(cmakeLists, builder.toString());
        return cmakeLists;
    }

    private String cmakePath(Path path) {
        return "\"" + path.toString().replace('\\', '/') + "\"";
    }
}


