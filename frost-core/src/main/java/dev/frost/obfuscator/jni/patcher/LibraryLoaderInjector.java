package dev.frost.obfuscator.jni.patcher;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Injects NativeLoader.load once into a class static initializer.
 */
public final class LibraryLoaderInjector {
    private static final String LOADER_OWNER = "dev/frost/obfuscator/jni/loader/NativeLoader";
    private static final String LOAD_DESCRIPTOR = "(Ljava/lang/String;)V";

    public void inject(ClassNode classNode, String libraryBaseName) {
        MethodNode clinit = findClinit(classNode);
        if (clinit == null) {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(loaderCall(libraryBaseName));
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            clinit.maxStack = 1;
            classNode.methods.add(clinit);
            return;
        }
        if (hasLoaderCall(clinit, libraryBaseName)) {
            return;
        }
        clinit.instructions.insert(loaderCall(libraryBaseName));
        clinit.maxStack = Math.max(clinit.maxStack, 1);
    }

    private MethodNode findClinit(ClassNode classNode) {
        return classNode.methods.stream()
                .filter(method -> "<clinit>".equals(method.name))
                .findFirst()
                .orElse(null);
    }

    private boolean hasLoaderCall(MethodNode method, String libraryBaseName) {
        boolean sawLibraryName = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode ldcInsn && libraryBaseName.equals(ldcInsn.cst)) {
                sawLibraryName = true;
            } else if (sawLibraryName && instruction instanceof MethodInsnNode methodInsn
                    && LOADER_OWNER.equals(methodInsn.owner)
                    && "load".equals(methodInsn.name)
                    && LOAD_DESCRIPTOR.equals(methodInsn.desc)) {
                return true;
            } else if (!(instruction instanceof LdcInsnNode)) {
                sawLibraryName = false;
            }
        }
        return false;
    }

    private InsnList loaderCall(String libraryBaseName) {
        InsnList instructions = new InsnList();
        instructions.add(new LdcInsnNode(libraryBaseName));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOADER_OWNER, "load", LOAD_DESCRIPTOR, false));
        return instructions;
    }
}


