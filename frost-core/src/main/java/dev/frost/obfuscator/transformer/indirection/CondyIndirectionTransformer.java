package dev.frost.obfuscator.transformer.indirection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public class CondyIndirectionTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BOOTSTRAP_METHOD_NAME = "__frost$condy$bootstrap";
    private static final String BOOTSTRAP_METHOD_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;";

    @Override
    public String getName() {
        return "condy-indirection";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int probability = clamp(getIntOption(config, "probability", 60), 0, 100);

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)) {
                return;
            }

            // Upgrade class bytecode version to Java 11 (V11 = 55) if below V11
            if ((classNode.version & 0xFFFF) < Opcodes.V11) {
                classNode.version = Opcodes.V11;
            }

            boolean modified = false;
            Handle bootstrapHandle = new Handle(
                    Opcodes.H_INVOKESTATIC,
                    classNode.name,
                    BOOTSTRAP_METHOD_NAME,
                    BOOTSTRAP_METHOD_DESC,
                    false
            );

            for (MethodNode method : classNode.methods) {
                if (method.instructions == null || AccessHelper.isInitializer(method)) continue;

                AbstractInsnNode insn = method.instructions.getFirst();
                while (insn != null) {
                    AbstractInsnNode next = insn.getNext();
                    if (insn instanceof LdcInsnNode ldc && ldc.cst != null) {
                        if (RANDOM.nextInt(100) < probability) {
                            ConstantDynamic condy = buildCondy(ldc.cst, bootstrapHandle);
                            if (condy != null) {
                                method.instructions.set(ldc, new LdcInsnNode(condy));
                                modified = true;
                            }
                        }
                    }
                    insn = next;
                }
            }

            if (modified) {
                ensureBootstrapMethod(classNode);
                pool.markDirty(classNode.name);
                detail("Applied Condy indirection in {}", classNode.name);
            }
        });
    }

    private ConstantDynamic buildCondy(Object cst, Handle bootstrapHandle) {
        if (cst instanceof String s) {
            return new ConstantDynamic("c$str", "Ljava/lang/String;", bootstrapHandle, s);
        } else if (cst instanceof Integer i) {
            return new ConstantDynamic("c$int", "I", bootstrapHandle, i);
        } else if (cst instanceof Long l) {
            return new ConstantDynamic("c$long", "J", bootstrapHandle, l);
        } else if (cst instanceof Float f) {
            return new ConstantDynamic("c$float", "F", bootstrapHandle, f);
        } else if (cst instanceof Double d) {
            return new ConstantDynamic("c$double", "D", bootstrapHandle, d);
        }
        return null;
    }

    private void ensureBootstrapMethod(ClassNode classNode) {
        for (MethodNode m : classNode.methods) {
            if (m.name.equals(BOOTSTRAP_METHOD_NAME)) return;
        }

        MethodNode mn = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                BOOTSTRAP_METHOD_NAME,
                BOOTSTRAP_METHOD_DESC,
                null,
                null
        );
        InsnList il = mn.instructions;
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new InsnNode(Opcodes.ARETURN));
        mn.maxStack = 1;
        mn.maxLocals = 4;
        classNode.methods.add(mn);
    }

    private int getIntOption(TransformerConfig config, String key, int defaultValue) {
        Object value = config.getOptions().get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
