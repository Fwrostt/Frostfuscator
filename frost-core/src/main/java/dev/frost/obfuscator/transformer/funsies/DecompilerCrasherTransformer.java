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
        int injected = 0;

        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)) {
                continue;
            }

            for (MethodNode method : classNode.methods) {
                if (method.instructions == null || method.instructions.size() < 4 || AccessHelper.isInitializer(method)) {
                    continue;
                }

                if (RANDOM.nextInt(100) < probability) {
                    injectMalformedTryCatch(method);
                    injected++;
                }
            }

            if (injected > 0) {
                pool.markDirty(classNode.name);
            }
        }
        log("Injected decompiler crasher blocks into {} methods", injected);
    }

    private void injectMalformedTryCatch(MethodNode method) {
        LabelNode lStart = new LabelNode(new Label());
        LabelNode lEnd = new LabelNode(new Label());
        LabelNode lHandler = new LabelNode(new Label());

        // Invert order: place lEnd BEFORE lStart or lStart == lEnd in tryCatchBlock
        method.tryCatchBlocks.add(new TryCatchBlockNode(lStart, lEnd, lHandler, "java/lang/Throwable"));
        method.tryCatchBlocks.add(new TryCatchBlockNode(lEnd, lStart, lHandler, null)); // Bad catch-all range

        InsnList handlerInstructions = new InsnList();
        handlerInstructions.add(lHandler);
        handlerInstructions.add(new InsnNode(Opcodes.ATHROW));
        handlerInstructions.add(lStart);
        handlerInstructions.add(new InsnNode(Opcodes.NOP));
        handlerInstructions.add(lEnd);

        method.instructions.insert(handlerInstructions);
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
