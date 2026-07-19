package dev.frost.obfuscator.transformer.funsies;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

public class BannerInjectionTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "inject-banner";
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
        String text = config.getOption("text", "Protected by Frostfuscator");
        int copies = Math.max(1, getIntOption(config, "copies", 1));
        int touched = 0;

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)
                    || AccessHelper.isAnnotation(classNode.access)) {
                continue;
            }
            Set<String> used = usedNames(classNode);
            for (int i = 0; i < copies; i++) {
                String fieldName = uniqueName(used, "__frost$banner$");
                classNode.fields.add(new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                        fieldName,
                        "Ljava/lang/String;",
                        null,
                        text
                ));
                classNode.methods.add(bannerMethod(uniqueName(used, "__frost$banner$"), fieldName, text, classNode.name));
            }
            pool.markDirty(classNode.name);
            touched++;
        }

        log("Injected banner text into {} classes", touched);
    }

    private MethodNode bannerMethod(String name, String fieldName, String text, String owner) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                "()Ljava/lang/String;",
                null,
                null
        );
        method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, fieldName, "Ljava/lang/String;"));
        method.instructions.add(new LdcInsnNode(text.length() ^ RANDOM.nextInt()));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 2;
        method.maxLocals = 0;
        return method;
    }

    private Set<String> usedNames(ClassNode classNode) {
        Set<String> used = new HashSet<>();
        for (FieldNode field : classNode.fields) used.add(field.name);
        for (MethodNode method : classNode.methods) used.add(method.name);
        return used;
    }

    private String uniqueName(Set<String> used, String prefix) {
        String name;
        do {
            name = prefix + Long.toHexString(RANDOM.nextLong());
        } while (!used.add(name));
        return name;
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
