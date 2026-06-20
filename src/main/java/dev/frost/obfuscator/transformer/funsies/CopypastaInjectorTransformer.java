package dev.frost.obfuscator.transformer.funsies;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.security.SecureRandom;
import java.util.List;

public class CopypastaInjectorTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> DEFAULT_LINES = List.of(
            "Skill issue.",
            "The fog is coming.",
            "You cannot stop the frost.",
            "Made with suffering.",
            "Reverse engineer detected.",
            "NullPointerException at line 0",
            "Unexpected token",
            "ArrayIndexOutOfBoundsException"
    );

    @Override
    public String getName() {
        return "copypasta-injector";
    }

    @Override
    public String getCategory() {
        return "Funsies";
    }

    @Override
    public Priority priority() {
        return Priority.FINAL;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int copies = Math.max(1, getIntOption(config, "copies", 3));
        int touched = 0;
        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)
                    || AccessHelper.isAnnotation(classNode.access)) {
                continue;
            }
            for (int i = 0; i < copies; i++) {
                classNode.fields.add(new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                        "__frost$copy$" + Long.toHexString(RANDOM.nextLong()),
                        "Ljava/lang/String;",
                        null,
                        DEFAULT_LINES.get(RANDOM.nextInt(DEFAULT_LINES.size()))
                ));
            }
            pool.markDirty(classNode.name);
            touched++;
        }
        log("Injected copypasta strings into {} classes", touched);
    }

    private int getIntOption(TransformerConfig config, String key, int fallback) {
        Object value = config.getOptions().get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
