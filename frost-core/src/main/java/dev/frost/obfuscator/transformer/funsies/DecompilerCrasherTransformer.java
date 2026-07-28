package dev.frost.obfuscator.transformer.funsies;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.security.SecureRandom;

public class DecompilerCrasherTransformer extends Transformer {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String getName() {
        return "decompiler-crasher";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int probability = clamp(getIntOption(config, "probability", 80), 0, 100);
        boolean includeGenerated = getBooleanOption(config, "include-generated",
                getBooleanOption(config, "include-synthetic", false));
        java.util.concurrent.atomic.LongAdder injected = new java.util.concurrent.atomic.LongAdder();

        pool.forEachClass(classNode -> {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)) {
                return;
            }

            int classInjected = 0;
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null
                        || method.instructions.size() < 4
                        || AccessHelper.isInitializer(method)
                        || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                        // Access Modifier can mark every ordinary method synthetic before this
                        // post-remap pass. Mapping reservations identify the actual generated
                        // String Splitting helpers without accidentally disabling this pass.
                        || (!includeGenerated
                        && mappings.isMethodPreserved(classNode.name, method.name, method.desc))) {
                    continue;
                }

                if (RANDOM.nextInt(100) < probability) {
                    injectDecompilerTrap(method);
                    injected.increment();
                    classInjected++;
                }
            }

            if (classInjected > 0) {
                pool.markDirty(classNode.name);
            }
        });
        log("Injected decompiler crasher blocks into {} methods", injected.sum());
    }

    private void injectDecompilerTrap(MethodNode method) {
        LabelNode handler = new LabelNode(new Label());
        LabelNode outerStart = new LabelNode(new Label());
        LabelNode innerStart = new LabelNode(new Label());
        LabelNode innerEnd = new LabelNode(new Label());
        LabelNode outerEnd = new LabelNode(new Label());
        LabelNode resume = new LabelNode(new Label());

        // Keep the protected blocks reachable to ASM's frame analyzer. A GOTO around an entirely
        // unreachable handler graph can make COMPUTE_FRAMES fail with negative frame indexes when
        // this is combined with large, already-obfuscated methods.
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                outerStart, outerEnd, handler, "java/lang/Throwable"));
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                innerStart, innerEnd, handler, null));

        InsnList trap = new InsnList();
        // The predicate is false at runtime, but both successors remain part of the verifier CFG.
        trap.add(new InsnNode(Opcodes.ICONST_0));
        trap.add(new JumpInsnNode(Opcodes.IFNE, outerStart));
        trap.add(new JumpInsnNode(Opcodes.GOTO, resume));
        trap.add(outerStart);
        trap.add(innerStart);
        trap.add(new InsnNode(Opcodes.ACONST_NULL));
        trap.add(new InsnNode(Opcodes.ATHROW));
        trap.add(innerEnd);
        trap.add(outerEnd);
        trap.add(handler);
        trap.add(new InsnNode(Opcodes.POP));
        trap.add(new JumpInsnNode(Opcodes.GOTO, resume));
        trap.add(resume);

        method.instructions.insert(trap);
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

    private boolean getBooleanOption(TransformerConfig config, String key, boolean defaultValue) {
        Object value = config.getOptions().get(key);
        if (value instanceof Boolean bool) return bool;
        return value == null ? defaultValue : Boolean.parseBoolean(value.toString());
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
