package dev.frost.obfuscator.integration;

import dev.frost.obfuscator.config.FrostJNIConfig;
import dev.frost.obfuscator.config.ObfuscationConfig;
import dev.frost.obfuscator.engine.ObfuscationEngine;
import dev.frost.obfuscator.testkit.FixtureJarFactory;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.transformer.TransformerRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ComprehensivePassIntegrationTest {
    @TempDir
    static Path tempDir;

    private static Path fixtureJar;
    private static List<String> fixtureClassEntries;

    @BeforeAll
    static void buildFixtureJar() throws Exception {
        fixtureJar = FixtureJarFactory.buildMulticlassJar(tempDir);
        fixtureClassEntries = FixtureJarFactory.classEntries(fixtureJar);
    }

    @Test
    void fixtureJarContainsSpecializedInputsForAllPassFamilies() throws Exception {
        try (JarFile jar = new JarFile(fixtureJar.toFile())) {
            assertEquals(FixtureJarFactory.MAIN_CLASS,
                    jar.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS));
            assertNotNull(jar.getEntry("plugin.yml"));
            assertNotNull(jar.getEntry("fixture-config.yml"));
            assertNotNull(jar.getEntry("data/database.bin"));
            assertTrue(fixtureClassEntries.size() >= 14, "Fixture should contain enough classes for hierarchy and rename tests");
            assertTrue(fixtureClassEntries.contains("com/acme/frostfixture/FixtureApp.class"));
            assertTrue(fixtureClassEntries.contains("com/acme/frostfixture/ServiceApi.class"));
            assertTrue(fixtureClassEntries.contains("com/acme/frostfixture/TracePrinter.class"));
            assertTrue(fixtureClassEntries.contains("com/acme/frostfixture/PluginEntrypoint.class"));
        }
    }

    @Test
    void passMatrixCoversEveryRegisteredTransformer() {
        assertEquals(new TreeSet<>(TransformerRegistry.getAllNames()), new TreeSet<>(cases().keySet()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("passCases")
    void everyRegisteredPassRunsAgainstMulticlassFixture(PassCase passCase) throws Exception {
        Path output = tempDir.resolve("out-" + safeName(passCase.name()) + ".jar");
        ObfuscationConfig config = baseConfig(output);
        Map<String, Object> options = new LinkedHashMap<>(passCase.options());
        if ("statistics-report".equals(passCase.name())) {
            options.put("output", tempDir.resolve("reports/statistics-report.json").toString());
        }
        TransformerConfig transformer = new TransformerConfig();
        transformer.setEnabled(true);
        transformer.setOptions(options);
        config.getTransformers().put(passCase.name(), transformer);

        new ObfuscationEngine(config, List.of(passCase.name())).run();

        assertTrue(Files.exists(output), passCase.name() + " did not write an output jar");
        try (JarFile outputJar = new JarFile(output.toFile())) {
            assertReadableClassEntries(outputJar);
            assertTrue(outputJar.size() > 0, "Output jar is empty");
            passCase.assertion().check(new RunResult(passCase.name(), output, outputJar));
        }
    }

    @Test
    void newProtectionStackRunsTogether() throws Exception {
        Path output = tempDir.resolve("out-new-protection-stack.jar");
        ObfuscationConfig config = baseConfig(output);
        enable(config, "anti-attach", options(
                "coverage", "entrypoints",
                "require-disable-attach", false,
                "reject-agents", false,
                "reject-attach-listener", false,
                "failure-action", "warn"));
        enable(config, "runtime-self-checksum", options("coverage", "entrypoints", "max-classes", 8, "failure-action", "throw"));
        enable(config, "structural-hardening", options("attributes-per-class", 2, "payload-bytes", 64));
        enable(config, "archive-extraction-canary", options("count", 1, "expanded-size", 65536, "seed", 1));

        new ObfuscationEngine(config, List.of("anti-attach", "runtime-self-checksum",
                "structural-hardening", "archive-extraction-canary")).run();

        try (JarFile jar = new JarFile(output.toFile())) {
            assertReadableClassEntries(jar);
            assertNoEntry(jar, "dev/frost/runtime/AntiAttachRuntime.class");
            assertNoEntry(jar, "dev/frost/runtime/SelfChecksumRuntime.class");
            assertHasEntry(jar, "META-INF/frostfuscator/runtime-checksums.tsv");
            assertHasEntry(jar, "META-INF/frostfuscator/canary/index.tsv");
            assertAnyClassBytesContain(jar, "-xx:+disableattachmechanism");
            assertAnyClassBytesContain(jar, "runtime-checksums.tsv");
            assertAnyClassBytesContain(jar, "FrostStructural");
        }
    }

    @Test
    void stringSplittingAndEncryptionRunTogetherEndToEnd() throws Exception {
        Path output = tempDir.resolve("out-string-splitting-encryption.jar");
        ObfuscationConfig config = baseConfig(output);
        enable(config, "string-splitting", options(
                "min-length", 2,
                "min-fragments", 3,
                "max-fragments", 24,
                "max-fragment-length", 3,
                "carrier-classes", 6,
                "decoys-per-string", 1,
                "encode-fragments", true,
                "seed", 42));
        enable(config, "string-encryption", options(
                "mode", "heavy",
                "min-length", 1,
                "max-method-instructions", 12_000));

        new ObfuscationEngine(config, List.of("string-splitting", "string-encryption")).run();

        try (JarFile jar = new JarFile(output.toFile())) {
            assertReadableClassEntries(jar);
            assertClassEntryCountEqualsFixture(jar);
            assertNoClassBytesContain(jar, "split-unicode-");
            assertNoClassBytesContain(jar, "split-field-literal");
        }

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{output.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> app = Class.forName(FixtureJarFactory.MAIN_CLASS, true, loader);
            Object instance = app.getConstructor().newInstance();
            Method run = app.getMethod("run", String[].class);
            String value = (String) run.invoke(instance, (Object) new String[]{"compatibility"});
            assertTrue(value.contains("split-unicode-\u2744\uFE0F-\uD83D\uDD25-\uD83C\uDF19"));
            assertTrue(value.contains("split-repeated-literal"));
            assertTrue(value.contains("split-field-literal"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void stringSplittingParticipatesInRenameAndIndirectionPipeline() throws Exception {
        Path output = tempDir.resolve("out-string-splitting-full-stack.jar");
        ObfuscationConfig config = baseConfig(output);
        enable(config, "string-splitting", options(
                "min-length", 2,
                "min-fragments", 3,
                "max-fragments", 16,
                "max-fragment-length", 3,
                "carrier-classes", 6,
                "indirection-depth", 2,
                "decoys-per-string", 1,
                "encode-fragments", true,
                "preserve-reflection-strings", true,
                "seed", 73));
        enable(config, "class-rename", options("mode", "aggressive"));
        enable(config, "method-rename", options("mode", "aggressive"));
        enable(config, "number-obfuscation", options(
                "probability", 100,
                "max-per-method", 128,
                "max-per-class", 512,
                "max-method-instructions", 12_000));
        enable(config, "mixed-boolean-arithmetic", options(
                "probability", 100,
                "rounds", 2,
                "operations", "add,sub,and,or,xor,neg",
                "max-per-method", 128,
                "max-per-class", 1024,
                "max-method-instructions", 12_000,
                "max-output-method-instructions", 24_000,
                "include-synthetic", false,
                "seed", 91));
        enable(config, "string-encryption", options(
                "mode", "heavy",
                "min-length", 1,
                "max-method-instructions", 12_000));
        enable(config, "reflection-hiding", options(
                "probability", 100,
                "owner-prefixes", "java/io,java/net,java/nio/file",
                "excluded-owners", "java/io/PrintStream,java/io/Console",
                "max-per-method", 64,
                "max-per-class", 256,
                "max-method-instructions", 12_000,
                "include-synthetic", false,
                "seed", 92));
        enable(config, "invoke-dynamic", options("probability", 100, "mutable-callsites", true));
        enable(config, "reference-hiding", options(
                "probability", 100,
                "max-per-class", 256,
                "max-method-instructions", 12_000));

        new ObfuscationEngine(config, List.of(
                "string-splitting",
                "class-rename",
                "method-rename",
                "number-obfuscation",
                "mixed-boolean-arithmetic",
                "string-encryption",
                "reflection-hiding",
                "invoke-dynamic",
                "reference-hiding"
        )).run();

        String mainClass;
        try (JarFile jar = new JarFile(output.toFile())) {
            assertReadableClassEntries(jar);
            assertClassEntryCountEqualsFixture(jar);
            mainClass = jar.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            assertNotEquals(FixtureJarFactory.MAIN_CLASS, mainClass);
            assertNoClassBytesContain(jar, "split-unicode-");
            assertNoClassBytesContain(jar, "split-field-literal");
            assertNoClassBytesContain(jar, "getHost");
        }

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{output.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> app = Class.forName(mainClass, true, loader);
            Method main = app.getMethod("main", String[].class);
            assertDoesNotThrow(() -> main.invoke(null, (Object) new String[]{"full-stack"}));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static Stream<PassCase> passCases() {
        return cases().values().stream();
    }

    private static Map<String, PassCase> cases() {
        Map<String, PassCase> cases = new LinkedHashMap<>();
        add(cases, "license-guard", options(
                "product", "FixtureProduct",
                "license-id", "integration",
                "failure-action", "warn",
                "coverage", "entrypoints",
                "inject-clinit", true), result -> assertHasEntry(result.jar(), "dev/frost/license/FrostLicenseRuntime.class"));
        add(cases, "language-mixup", options("style", "cpp", "rename-classes", true, "rename-methods", true), RunResult::assertBasicOutput);
        add(cases, "class-rename", options("mode", "aggressive"), result -> assertManifestMainPresent(result.jar()));
        add(cases, "field-rename", options("mode", "aggressive"), RunResult::assertBasicOutput);
        add(cases, "method-rename", options("mode", "aggressive"), RunResult::assertBasicOutput);
        add(cases, "local-variable-rename", options(), RunResult::assertBasicOutput);
        add(cases, "remove-debug", options("remove-source-file", true, "remove-line-numbers", true, "remove-local-variables", true, "remove-parameters", true), RunResult::assertBasicOutput);
        add(cases, "string-splitting", options("min-length", 2, "min-fragments", 2, "max-fragments", 12, "max-fragment-length", 4, "carrier-classes", 4, "indirection-depth", 1, "decoys-per-string", 1, "encode-fragments", true, "preserve-reflection-strings", true, "seed", 1), result -> assertClassEntryCountEqualsFixture(result.jar()));
        add(cases, "string-encryption", options("mode", "heavy", "min-length", 1, "max-method-instructions", 6000), RunResult::assertBasicOutput);
        add(cases, "number-obfuscation", options("probability", 100, "max-per-method", 128, "max-per-class", 512, "max-method-instructions", 6000), RunResult::assertBasicOutput);
        add(cases, "mixed-boolean-arithmetic", options("probability", 100, "rounds", 2, "operations", "add,sub,and,or,xor,neg", "max-per-method", 128, "max-per-class", 1024, "max-method-instructions", 6000, "max-output-method-instructions", 16000, "include-synthetic", false, "seed", 1), RunResult::assertBasicOutput);
        add(cases, "parameter-encryption", options("probability", 100), RunResult::assertBasicOutput);
        add(cases, "flow-obfuscation", options(
                "mode", "heavy",
                "exception-guards", true,
                "stack-noise", true,
                "flatten", true,
                "flatten-probability", 100,
                "flatten-min-blocks", 3,
                "flatten-max-blocks", 96,
                "flatten-min-complexity", 1,
                "flatten-cost-budget", 2048,
                "dispatcher-styles", "lookup,table,computed,nested,split",
                "partial-flattening-rate", 35,
                "partial-region-rate", 55,
                "flatten-hot-loops", false,
                "state-reencode-rate", 100,
                "fake-dispatcher-states", 4,
                "block-clone-rate", 50,
                "max-exception-handlers", 0,
                "predicate-rate", 100,
                "max-predicates-per-method", 8,
                "predicate-families", "arithmetic,bitwise,reversible,modular,lookup-table,stateful,argument-derived,interprocedural",
                "predicate-sources", "volatile,thread,environment,time",
                "predicate-cost-budget", 256,
                "predicate-camouflage-rate", 50,
                "predicate-local-rate", 35,
                "heavy-predicates-in-loops", false,
                "hot-loop-max-predicate-cost", 2,
                "min-method-instructions", 4,
                "max-method-instructions", 5000,
                "max-output-method-instructions", 16000,
                "include-synthetic", false,
                "seed", 20260719
        ), RunResult::assertBasicOutput);
        add(cases, "flow-outliner", options("probability", 100, "max-per-class", 8), RunResult::assertBasicOutput);
        add(cases, "flow-range", options("probability", 100), RunResult::assertBasicOutput);
        add(cases, "flow-condition", options("probability", 100, "max-per-method", 8), RunResult::assertBasicOutput);
        add(cases, "flow-exception", options("strength", "GOOD"), RunResult::assertBasicOutput);
        add(cases, "flow-switch", options("probability", 100), RunResult::assertBasicOutput);
        add(cases, "stack-manipulation", options("probability", 100, "max-per-method", 8), RunResult::assertBasicOutput);
        add(cases, "reflection-hiding", options("probability", 100, "owner-prefixes", "java/io,java/net,java/nio/file", "excluded-owners", "java/io/PrintStream,java/io/Console", "max-per-method", 64, "max-per-class", 256, "max-method-instructions", 6000, "include-synthetic", false, "seed", 1), result -> assertNoClassBytesContain(result.jar(), "getHost"));
        add(cases, "invoke-dynamic", options("probability", 100, "mutable-callsites", true), RunResult::assertBasicOutput);
        add(cases, "reference-hiding", options("probability", 100, "max-per-class", 32, "max-method-instructions", 6000), RunResult::assertBasicOutput);
        add(cases, "access-modifier", options("synthetic", true, "bridge-methods", false, "relax-final", false), RunResult::assertBasicOutput);
        add(cases, "metadata-noise", options("strings-per-class", 3, "deprecated", true, "signatures", true), result -> assertAnyClassHasDeprecatedAccess(result.jar()));
        add(cases, "watermark", options("owner", "Integration", "id", "fixture", "class-annotations", true, "string-field", true, "field-name", "__frost$watermark"), result -> assertHasEntry(result.jar(), "META-INF/frostfuscator/watermark.properties"));
        add(cases, "integrity", options(), result -> assertHasEntry(result.jar(), "META-INF/frostfuscator/integrity.sha256"));
        add(cases, "anti-debug", options("method-name", "__frost$antiDebug", "check-arguments", true, "check-debug-classes", false, "check-stack", false, "check-timing", false, "shared-helper", true), result -> assertAnyClassBytesContain(result.jar(), "__frost$antiDebug"));
        add(cases, "anti-attach", options("coverage", "entrypoints", "require-disable-attach", false, "reject-agents", false, "reject-attach-listener", false, "failure-action", "warn"), result -> {
            assertNoEntry(result.jar(), "dev/frost/runtime/AntiAttachRuntime.class");
            assertAnyClassBytesContain(result.jar(), "-xx:+disableattachmechanism");
        });
        add(cases, "runtime-self-checksum", options("coverage", "entrypoints", "max-classes", 8, "failure-action", "throw"), result -> {
            assertNoEntry(result.jar(), "dev/frost/runtime/SelfChecksumRuntime.class");
            assertHasEntry(result.jar(), "META-INF/frostfuscator/runtime-checksums.tsv");
            assertAnyClassBytesContain(result.jar(), "runtime-checksums.tsv");
        });
        add(cases, "anti-decompiler", options(), result -> assertAnyClassBytesContain(result.jar(), "__frost$decompiler$trap"));
        add(cases, "structural-hardening", options("attributes-per-class", 2, "payload-bytes", 64, "method-attributes", true, "field-attributes", true), result -> assertAnyClassBytesContain(result.jar(), "FrostStructural"));
        add(cases, "archive-extraction-canary", options("count", 2, "expanded-size", 65536, "seed", 1), result -> assertHasEntry(result.jar(), "META-INF/frostfuscator/canary/index.tsv"));
        add(cases, "junk-code", options("min-methods-per-class", 1, "max-methods-per-class", 1, "min-fields-per-class", 1, "max-fields-per-class", 1, "seed", 1), result -> assertAnyClassBytesContain(result.jar(), "__frost$"));
        add(cases, "fake-application", options("profiles", "enterprise", "classes-per-profile", 2, "min-methods-per-class", 2, "max-methods-per-class", 3, "min-fields-per-class", 1, "max-fields-per-class", 2, "seed", 1), result -> assertClassEntryCountGreaterThanFixture(result.jar()));
        add(cases, "fake-classes", options("priority", "pre-obfuscation", "count", 3, "min-methods-per-class", 2, "max-methods-per-class", 3, "min-fields-per-class", 1, "max-fields-per-class", 2, "seed", 1), result -> assertClassEntryCountGreaterThanFixture(result.jar()));
        add(cases, "classloader-encryption", options("encryptMainClass", false, "algorithm", "AES/GCM/NoPadding", "resourcePath", "classes.db", "compressClasses", true, "failOnError", true), result -> assertHasEntry(result.jar(), "classes.db"));
        add(cases, "virtualization", options("probability", 100, "min-method-instructions", 4, "max-method-instructions", 180, "skip-initializers", true, "encrypt-bytecode", true, "max-locals", 128, "max-stack", 256, "seed", 1), result -> assertAnyClassBytesContain(result.jar(), "FrostVM"));
        add(cases, "inject-banner", options("text", "INTEGRATION BANNER", "copies", 1), result -> assertAnyClassBytesContain(result.jar(), "INTEGRATION BANNER"));
        add(cases, "emoji-hell", options("copies", 1), RunResult::assertBasicOutput);
        add(cases, "copypasta-injector", options("copies", 1), RunResult::assertBasicOutput);
        add(cases, "chinese-mode", options("package-mode", "none", "rename-members", true, "inject-fun", true, "large-banners", false, "quotes", true, "inject-metadata", true, "inject-strings", true, "min-fun-members", 1, "max-fun-members", 1), RunResult::assertBasicOutput);
        add(cases, "decompiler-zip-ties", options("generic-depth", 24, "fields-per-class", 1), result -> assertAnyClassBytesContain(result.jar(), "__frost$zip$tie"));
        add(cases, "troll-stack-traces", options("message", "TRACE REDACTED BY TEST"), result -> assertAnyClassBytesContain(result.jar(), "__frost$troll$trace"));
        add(cases, "resource-compression", options("remove-originals", false, "output-prefix", "META-INF/frostfuscator/resources/"), result -> assertHasEntry(result.jar(), "META-INF/frostfuscator/resource-index.txt"));
        add(cases, "resource-encryption", options("remove-originals", false, "output-prefix", "META-INF/frostfuscator/encrypted/", "seed", 1), result -> assertHasEntry(result.jar(), "META-INF/frostfuscator/resource-encryption-index.txt"));
        add(cases, "resource-splitting", options("part-size", 512, "minimum-size", 1, "remove-originals", false), result -> assertHasEntry(result.jar(), "META-INF/frostfuscator/splits/index.tsv"));
        add(cases, "resource-steganography", options("password", "integration-password", "extensions", "yml,bin", "remove-originals", false), result -> assertHasEntry(result.jar(), "META-INF/frostfuscator/stego/index.tsv"));
        add(cases, "aggressive-inlining", options("max-instructions", 16, "remove-inlined-methods", true), RunResult::assertBasicOutput);
        add(cases, "dead-code-elimination", options("remove-private-fields", true), RunResult::assertBasicOutput);
        add(cases, "bytecode-optimizer", options(), RunResult::assertBasicOutput);
        add(cases, "jar-shrinker", options(), RunResult::assertBasicOutput);
        add(cases, "statistics-report", options("format", "json"), result -> assertTrue(Files.exists(tempDir.resolve("reports/statistics-report.json"))));
        return cases;
    }

    private static ObfuscationConfig baseConfig(Path output) {
        ObfuscationConfig config = new ObfuscationConfig();
        config.setInput(fixtureJar.toString());
        config.setOutput(output.toString());
        config.setDictionary("alphabet");
        config.setPackageMode("keep");
        config.setFlattenPackage("obf");
        config.setExclusions(List.of());
        config.setInclusions(List.of());
        config.getLibraries().setRuntime(false);
        config.getLibraries().setRecursive(false);
        config.getLibraries().setStrict(false);
        ObfuscationConfig.MappingConfig mapping = new ObfuscationConfig.MappingConfig();
        mapping.setEnabled(false);
        config.setMapping(mapping);
        FrostJNIConfig frostJNI = new FrostJNIConfig();
        frostJNI.setEnabled(false);
        config.setFrostJNI(frostJNI);
        return config;
    }

    private static void enable(ObfuscationConfig config, String name, Map<String, Object> options) {
        TransformerConfig transformer = new TransformerConfig();
        transformer.setEnabled(true);
        transformer.setOptions(options);
        config.getTransformers().put(name, transformer);
    }

    private static void add(Map<String, PassCase> cases, String name, Map<String, Object> options, JarAssertion assertion) {
        cases.put(name, new PassCase(name, options, assertion));
    }

    private static Map<String, Object> options(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    private static void assertReadableClassEntries(JarFile jar) throws IOException {
        for (JarEntry entry : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
            try (var input = jar.getInputStream(entry)) {
                ClassNode node = new ClassNode();
                new ClassReader(input.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
                assertNotNull(node.name, "Could not read class " + entry.getName());
            }
        }
    }

    private static void assertManifestMainPresent(JarFile jar) throws IOException {
        assertNotNull(jar.getManifest());
        assertNotNull(jar.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS));
    }

    private static void assertHasEntry(JarFile jar, String name) {
        assertNotNull(jar.getEntry(name), "Missing jar entry " + name);
    }

    private static void assertNoEntry(JarFile jar, String name) {
        assertNull(jar.getEntry(name), "Unexpected plain runtime entry " + name);
    }

    private static void assertClassEntryCountGreaterThanFixture(JarFile jar) {
        long count = jar.stream().filter(entry -> entry.getName().endsWith(".class")).count();
        assertTrue(count > fixtureClassEntries.size(), "Expected generated classes to increase class entry count");
    }

    private static void assertClassEntryCountEqualsFixture(JarFile jar) {
        long count = jar.stream().filter(entry -> entry.getName().endsWith(".class")).count();
        assertEquals(fixtureClassEntries.size(), count,
                "String splitting should reuse existing classes rather than generate carrier classes");
    }

    private static void assertAnyClassBytesContain(JarFile jar, String needle) throws IOException {
        byte[] needleBytes = needle.getBytes(StandardCharsets.UTF_8);
        for (JarEntry entry : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
            try (var input = jar.getInputStream(entry)) {
                if (contains(input.readAllBytes(), needleBytes)) {
                    return;
                }
            }
        }
        fail("No class entry contained '" + needle + "'");
    }

    private static void assertNoClassBytesContain(JarFile jar, String needle) throws IOException {
        byte[] needleBytes = needle.getBytes(StandardCharsets.UTF_8);
        for (JarEntry entry : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
            try (var input = jar.getInputStream(entry)) {
                assertFalse(contains(input.readAllBytes(), needleBytes),
                        "Class entry still contains complete string '" + needle + "': " + entry.getName());
            }
        }
    }

    private static void assertAnyClassHasDeprecatedAccess(JarFile jar) throws IOException {
        for (JarEntry entry : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
            try (var input = jar.getInputStream(entry)) {
                ClassNode node = new ClassNode();
                new ClassReader(input.readAllBytes()).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
                if ((node.access & 0x20000) != 0) {
                    return;
                }
            }
        }
        fail("No class was marked deprecated by metadata-noise");
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0) {
            return true;
        }
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            int j = 0;
            while (j < needle.length && haystack[i + j] == needle[j]) {
                j++;
            }
            if (j == needle.length) {
                return true;
            }
        }
        return false;
    }

    private static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private record PassCase(String name, Map<String, Object> options, JarAssertion assertion) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record RunResult(String passName, Path output, JarFile jar) {
        void assertBasicOutput() {
            assertTrue(output.toFile().length() > 0, passName + " wrote an empty jar");
        }
    }

    @FunctionalInterface
    private interface JarAssertion {
        void check(RunResult result) throws Exception;
    }
}
