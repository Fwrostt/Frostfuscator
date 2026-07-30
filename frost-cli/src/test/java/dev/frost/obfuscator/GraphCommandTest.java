package dev.frost.obfuscator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import picocli.CommandLine;
import java.nio.file.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class GraphCommandTest {
    @TempDir Path temporary;

    @Test void headlessGraphCommandExportsJson() throws Exception {
        Path input = temporary.resolve("fixture.jar"), output = temporary.resolve("graph.json");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input))) {
            jar.putNextEntry(new JarEntry("fixture/A.class")); jar.write(classBytes()); jar.closeEntry();
        }
        int exit = new CommandLine(new Main()).execute("graph", "-i", input.toString(), "--type", "dependencies",
                "--format", "json", "-o", output.toString());
        assertEquals(0, exit); assertTrue(Files.readString(output).contains("CLASS_DEPENDENCY"));
    }
    private static byte[] classBytes() {
        ClassWriter writer = new ClassWriter(0); writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/A", null, "java/lang/Object", null);
        writer.visitEnd(); return writer.toByteArray();
    }
}
