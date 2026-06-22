package dev.frost.obfuscator.jni.generator.cpp;

import dev.frost.obfuscator.jni.core.ir.IRClass;
import dev.frost.obfuscator.jni.core.ir.IRMethod;
import org.objectweb.asm.Opcodes;

/**
 * Class-level C++ generator for FrostJNI IR.
 */
public final class CppGenerator {
    private final JniNameMangler nameMangler = new JniNameMangler();
    private final CppRuntimeGenerator runtimeGenerator = new CppRuntimeGenerator();
    private final CppMethodGenerator methodGenerator = new CppMethodGenerator(nameMangler);

    public GeneratedCppClass generate(IRClass irClass) {
        StringBuilder source = new StringBuilder();
        String fileName = nameMangler.fileName(irClass.internalName());
        String cacheSuffix = fileName.substring(0, fileName.length() - ".cpp".length()).replaceAll("[^A-Za-z0-9_]", "_");
        runtimeGenerator.appendPrelude(source, cacheSuffix);
        for (IRMethod method : irClass.methods()) {
            if (shouldGenerate(method)) {
                methodGenerator.appendMethod(source, method, cacheSuffix);
                source.append('\n');
            }
        }
        return new GeneratedCppClass(fileName, source.toString());
    }

    private boolean shouldGenerate(IRMethod method) {
        return !method.name().startsWith("<")
                && (method.access() & Opcodes.ACC_ABSTRACT) == 0
                && (method.access() & Opcodes.ACC_NATIVE) == 0;
    }
}


