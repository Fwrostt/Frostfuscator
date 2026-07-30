package dev.frost.obfuscator.jni.compiler;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZigCompilerCommandBuilderTest {
    @Test
    void usesZigCppDriverWithSharedLibraryFlags() {
        Path root = Path.of("build", "zig-command-test").toAbsolutePath();
        CompilerInput input = new CompilerInput(root, root, List.of(TargetPlatform.current()),
                "fixture", true, "O2", false);
        CompilerEnvironment environment = new CompilerEnvironment(Path.of("zig"), List.of(root), Map.of(), "Zig");

        CompilerCommand command = new ZigCompilerCommandBuilder().build(input, environment,
                TargetPlatform.current(), List.of(root.resolve("fixture.cpp")), root.resolve("fixture.so"));

        assertEquals(List.of("zig", "c++"), command.command().subList(0, 2));
        assertTrue(command.command().contains("-shared"));
        assertTrue(command.command().contains("-std=c++17"));
    }
}
