package dev.frost.obfuscator.transformer.protection;

import dev.frost.obfuscator.engine.ClassPool;
import dev.frost.obfuscator.remapper.MappingCollector;
import dev.frost.obfuscator.transformer.Transformer;
import dev.frost.obfuscator.transformer.TransformerConfig;
import dev.frost.obfuscator.util.AccessHelper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.List;

public class AntiAgentTransformer extends Transformer {

    private static final String RUNTIME_CLASS = "dev/frost/runtime/AntiAgentRuntime";

    @Override
    public String getName() {
        return "anti-agent";
    }

    @Override
    public boolean runsPostRemap() {
        return true;
    }

    @Override
    public void transform(ClassPool pool, MappingCollector mappings, TransformerConfig config) {
        int injected = 0;
        for (ClassNode classNode : pool.getClasses()) {
            if (!shouldProcess(classNode.name, config, pool.getGlobalExclusions(), pool.getGlobalInclusions())
                    || AccessHelper.isInterface(classNode.access)) {
                continue;
            }

            boolean hasMain = false;
            for (MethodNode method : classNode.methods) {
                if (method.name.equals("main") && method.desc.equals("([Ljava/lang/String;)V")) {
                    hasMain = true;
                    injectAgentCheck(method);
                    break;
                }
            }

            if (!hasMain) {
                MethodNode clinit = getOrCreateClinit(classNode);
                injectAgentCheck(clinit);
            }

            pool.markDirty(classNode.name);
            injected++;
        }
        log("Injected anti-agent protection into {} classes", injected);
    }

    private void injectAgentCheck(MethodNode method) {
        InsnList il = new InsnList();
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_CLASS, "checkInstrumentationAndAgents", "()V", false));
        method.instructions.insert(il);
    }

    private MethodNode getOrCreateClinit(ClassNode classNode) {
        for (MethodNode m : classNode.methods) {
            if (m.name.equals("<clinit>")) return m;
        }
        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(clinit);
        return clinit;
    }
}
