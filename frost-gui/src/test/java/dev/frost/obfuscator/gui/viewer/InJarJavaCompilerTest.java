package dev.frost.obfuscator.gui.viewer;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InJarJavaCompilerTest {

    @Test
    void testCompileSimpleClassInMemory() {
        InJarJavaCompiler compiler = new InJarJavaCompiler();
        String source = """
                package com.example;
                public class Hello {
                    public String greeting() {
                        return "Hello World";
                    }
                }
                """;

        Map<String, byte[]> classPool = new HashMap<>();
        InJarJavaCompiler.CompilationResult result = compiler.compile("com/example/Hello", source, classPool);

        assertTrue(result.success(), "Compilation should succeed for valid Java code");
        assertNotNull(result.compiledClasses());
        assertTrue(result.compiledClasses().containsKey("com/example/Hello"));
        assertTrue(result.compiledClasses().get("com/example/Hello").length > 0);
    }

    @Test
    void testCompileClassWithDotClassSuffix() {
        InJarJavaCompiler compiler = new InJarJavaCompiler();
        String source = """
                package com.acme.frostfixture;
                public final class FixtureApp {
                    public static void main(String[] args) {
                        System.out.println("Hello");
                    }
                }
                """;

        Map<String, byte[]> classPool = new HashMap<>();
        InJarJavaCompiler.CompilationResult result = compiler.compile("com/acme/frostfixture/FixtureApp.class", source, classPool);

        assertTrue(result.success(), "Compilation should succeed when className has .class extension: " + result.diagnostics());
        assertNotNull(result.compiledClasses());
        assertTrue(result.compiledClasses().containsKey("com/acme/frostfixture/FixtureApp"));
    }

    @Test
    void testCompileWithBase64Import() {
        InJarJavaCompiler compiler = new InJarJavaCompiler();
        String source = """
                package com.acme.frostfixture;
                import java.util.Base64;

                public final class FixtureApp {
                    public static void main(String[] args) {
                        String encoded = Base64.getEncoder().encodeToString("test".getBytes());
                        System.out.println(encoded);
                    }
                }
                """;

        Map<String, byte[]> classPool = new HashMap<>();
        InJarJavaCompiler.CompilationResult result = compiler.compile("com/acme/frostfixture/FixtureApp.class", source, classPool);

        assertTrue(result.success(), "Compilation with Base64 import should succeed: " + result.diagnostics());
        assertNotNull(result.compiledClasses());
        assertTrue(result.compiledClasses().containsKey("com/acme/frostfixture/FixtureApp"));
    }
}
