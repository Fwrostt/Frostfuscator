package dev.frost.obfuscator.jni.desugar;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.util.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites supported invokedynamic instructions before FrostJNI IR generation.
 */
public final class InvokeDynamicDesugarer {
    private final LambdaMetafactoryDesugarer lambdaDesugarer = new LambdaMetafactoryDesugarer();
    private final ObjectMethodsDesugarer objectMethodsDesugarer = new ObjectMethodsDesugarer();

    public DesugarReport desugar(ClassPool pool) {
        List<ClassNode> originalClasses = new ArrayList<>(pool.getClasses());
        List<ClassNode> generatedClasses = new ArrayList<>();
        int lambdasRewritten = 0;
        int objectMethodsRewritten = 0;
        int unsupported = 0;

        for (ClassNode classNode : originalClasses) {
            int classGenerated = 0;
            boolean dirty = false;
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null || method.instructions.size() == 0) {
                    continue;
                }
                List<InvokeDynamicInsnNode> dynamicInstructions = dynamicInstructions(method);
                for (InvokeDynamicInsnNode dynamicInsn : dynamicInstructions) {
                    if (isLambdaMetafactory(dynamicInsn)) {
                        LambdaMetafactoryDesugarer.DesugarResult result =
                                lambdaDesugarer.desugar(classNode, method, dynamicInsn, classGenerated++);
                        if (result.supported()) {
                            generatedClasses.add(result.generatedClass());
                            lambdasRewritten++;
                            dirty = true;
                        } else {
                            unsupported++;
                        }
                    } else if (isObjectMethods(dynamicInsn)) {
                        ObjectMethodsDesugarer.DesugarResult result = objectMethodsDesugarer.desugar(method, dynamicInsn);
                        if (result.isSupported()) {
                            objectMethodsRewritten++;
                            dirty = true;
                        } else {
                            unsupported++;
                        }
                    } else if (isSupportedWithoutDesugar(dynamicInsn)) {
                        // StringConcatFactory is lowered directly by the C++ generator.
                    } else {
                        unsupported++;
                    }
                }
            }
            if (dirty) {
                pool.markDirty(classNode.name);
            }
        }

        for (ClassNode generatedClass : generatedClasses) {
            pool.addClass(generatedClass.name, generatedClass);
            pool.markDirty(generatedClass.name);
        }

        if (lambdasRewritten > 0 || objectMethodsRewritten > 0 || unsupported > 0) {
            Logger.info("[FrostJNI] Desugared {} LambdaMetafactory invokedynamic site{} into {} generated class{}",
                    lambdasRewritten,
                    lambdasRewritten == 1 ? "" : "s",
                    generatedClasses.size(),
                    generatedClasses.size() == 1 ? "" : "es");
            if (objectMethodsRewritten > 0) {
                Logger.info("[FrostJNI] Desugared {} ObjectMethods invokedynamic site{}",
                        objectMethodsRewritten,
                        objectMethodsRewritten == 1 ? "" : "s");
            }
            if (unsupported > 0) {
                Logger.warn("[FrostJNI] {} invokedynamic site{} still require native backend support",
                        unsupported,
                        unsupported == 1 ? "" : "s");
            }
        }

        return new DesugarReport(lambdasRewritten, objectMethodsRewritten, unsupported, generatedClasses.size());
    }

    private List<InvokeDynamicInsnNode> dynamicInstructions(MethodNode method) {
        List<InvokeDynamicInsnNode> dynamicInstructions = new ArrayList<>();
        AbstractInsnNode instruction = method.instructions.getFirst();
        while (instruction != null) {
            if (instruction instanceof InvokeDynamicInsnNode dynamicInsn) {
                dynamicInstructions.add(dynamicInsn);
            }
            instruction = instruction.getNext();
        }
        return dynamicInstructions;
    }

    private boolean isLambdaMetafactory(InvokeDynamicInsnNode instruction) {
        return instruction.bsm != null
                && "java/lang/invoke/LambdaMetafactory".equals(instruction.bsm.getOwner())
                && ("metafactory".equals(instruction.bsm.getName()) || "altMetafactory".equals(instruction.bsm.getName()));
    }

    private boolean isSupportedWithoutDesugar(InvokeDynamicInsnNode instruction) {
        return instruction.bsm != null
                && "java/lang/invoke/StringConcatFactory".equals(instruction.bsm.getOwner());
    }

    private boolean isObjectMethods(InvokeDynamicInsnNode instruction) {
        return instruction.bsm != null
                && "java/lang/runtime/ObjectMethods".equals(instruction.bsm.getOwner());
    }

    public record DesugarReport(
            int lambdasRewritten,
            int objectMethodsRewritten,
            int unsupportedDynamicSites,
            int generatedClasses
    ) {
    }
}
