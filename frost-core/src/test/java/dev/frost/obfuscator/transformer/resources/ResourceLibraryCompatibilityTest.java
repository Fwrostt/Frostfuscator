package dev.frost.obfuscator.transformer.resources;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.engine.JarProcessor;
import dev.frost.obfuscator.engine.ProtectionStats;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Context;
import dev.frost.obfuscator.transformer.TransformerConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceLibraryCompatibilityTest {
    private static final String LIBRARY_RESOURCE = "com/sun/glass/ui/win/themes.properties";
    private static final String APPLICATION_RESOURCE = "dev/frost/app/config.properties";

    @Test
    void compressionKeepsResourcesOwnedByPreservedLibraries() {
        Context context = context();
        context.config().getOptions().put("remove-originals", true);

        new ResourceCompressionTransformer().transform(context);

        assertLibraryResourcePreserved(context);
        assertFalse(context.resources().containsKey(APPLICATION_RESOURCE));
        assertTrue(context.resources().containsKey(
                "META-INF/frostfuscator/resources/" + APPLICATION_RESOURCE + ".gz"));
    }

    @Test
    void encryptionKeepsResourcesOwnedByPreservedLibraries() {
        Context context = context();
        context.config().getOptions().put("remove-originals", true);
        context.config().getOptions().put("seed", 1);

        new ResourceEncryptionTransformer().transform(context);

        assertLibraryResourcePreserved(context);
        assertFalse(context.resources().containsKey(APPLICATION_RESOURCE));
        assertTrue(context.resources().containsKey(
                "META-INF/frostfuscator/encrypted/" + APPLICATION_RESOURCE + ".frz"));
    }

    @Test
    void splittingKeepsResourcesOwnedByPreservedLibraries() {
        Context context = context();
        context.config().getOptions().put("remove-originals", true);
        context.config().getOptions().put("minimum-size", 1);
        context.config().getOptions().put("part-size", 256);

        new ResourceSplittingTransformer().transform(context);

        assertLibraryResourcePreserved(context);
        assertFalse(context.resources().containsKey(APPLICATION_RESOURCE));
        assertTrue(context.resources().containsKey("META-INF/frostfuscator/splits/index.tsv"));
    }

    @Test
    void steganographyKeepsResourcesOwnedByPreservedLibraries() {
        Context context = context();
        context.config().getOptions().put("remove-originals", true);
        context.config().getOptions().put("password", "test-password");
        context.config().getOptions().put("extensions", "properties");

        new ResourceSteganographyTransformer().transform(context);

        assertLibraryResourcePreserved(context);
        assertFalse(context.resources().containsKey(APPLICATION_RESOURCE));
        assertTrue(context.resources().containsKey("META-INF/frostfuscator/stego/index.tsv"));
    }

    private Context context() {
        ClassPool pool = new ClassPool();
        ClassNode libraryClass = classNode("com/sun/glass/ui/win/WinApplication");
        ClassNode applicationClass = classNode("dev/frost/app/Main");
        pool.addClass(libraryClass.name, libraryClass);
        pool.excludeFromTransformation(libraryClass.name, "auto-detected embedded library");
        pool.addClass(applicationClass.name, applicationClass);

        JarProcessor jar = new JarProcessor();
        jar.putResource(LIBRARY_RESOURCE, "javafx theme".getBytes(StandardCharsets.UTF_8));
        jar.putResource(APPLICATION_RESOURCE, "application config".getBytes(StandardCharsets.UTF_8));
        return new Context(pool, jar, new MappingCollector(), new TransformerConfig(),
                new ProtectionStats(), Path.of("input.jar"), Path.of("output.jar"));
    }

    private ClassNode classNode(String name) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";
        return node;
    }

    private void assertLibraryResourcePreserved(Context context) {
        assertTrue(context.resources().containsKey(LIBRARY_RESOURCE));
        assertFalse(context.resources().keySet().stream()
                .anyMatch(name -> name.contains("com/sun/glass/ui/win/themes.properties")
                        && !name.equals(LIBRARY_RESOURCE)));
    }
}
