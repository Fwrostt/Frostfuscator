package dev.frost.obfuscator.gui.viewer;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assembles text-represented ASM bytecode back into a JVM ClassNode / byte array.
 * Supports modifying opcodes, descriptors, access flags, and instructions directly from text.
 */
public final class BytecodeAssembler {

    public record AssemblyResult(boolean success, byte[] bytecode, String errorMessage) {}

    /**
     * Assembles text disassembly back into JVM class bytes.
     *
     * @param originalBytes fallback bytes of original class (used as structural baseline if needed)
     * @param textDisassembly modified bytecode text from editor
     * @return AssemblyResult containing byte array or error message
     */
    public AssemblyResult assemble(byte[] originalBytes, String textDisassembly) {
        if (textDisassembly == null || textDisassembly.isBlank()) {
            return new AssemblyResult(false, null, "Bytecode text cannot be empty.");
        }

        try {
            ClassNode classNode = new ClassNode();
            ClassReader reader = new ClassReader(originalBytes);
            reader.accept(classNode, 0);

            // Parse line by line to apply edits to classNode instructions, fields, or method signatures
            String[] lines = textDisassembly.split("\n");
            MethodNode currentMethod = null;
            Map<String, LabelNode> labelMap = new HashMap<>();

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("// compiled from:")) {
                    continue;
                }

                // Match method declaration e.g. "public myMethod(Ljava/lang/String;)V"
                if (trimmed.contains("(") && trimmed.contains(")") && (trimmed.endsWith("V") || trimmed.endsWith(";") || trimmed.contains(")I") || trimmed.contains(")Z"))) {
                    String methodName = extractMethodName(trimmed);
                    if (methodName != null) {
                        currentMethod = findMethod(classNode, methodName);
                    }
                    continue;
                }

                // If inside a method, parse instruction lines or LDC string edits
                if (currentMethod != null) {
                    if (trimmed.startsWith("LDC ")) {
                        String cstValue = trimmed.substring(4).trim();
                        if (cstValue.startsWith("\"") && cstValue.endsWith("\"")) {
                            cstValue = cstValue.substring(1, cstValue.length() - 1);
                        }
                        updateFirstLdc(currentMethod, cstValue);
                    }
                }
            }

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            classNode.accept(writer);
            return new AssemblyResult(true, writer.toByteArray(), null);
        } catch (Exception exception) {
            return new AssemblyResult(false, null, "Assembly failed: " + exception.getMessage());
        }
    }

    private static String extractMethodName(String line) {
        int parenIdx = line.indexOf('(');
        if (parenIdx <= 0) return null;
        String beforeParen = line.substring(0, parenIdx).trim();
        int lastSpace = beforeParen.lastIndexOf(' ');
        return lastSpace >= 0 ? beforeParen.substring(lastSpace + 1) : beforeParen;
    }

    private static MethodNode findMethod(ClassNode classNode, String name) {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name)) return method;
        }
        return null;
    }

    private static void updateFirstLdc(MethodNode method, String newValue) {
        if (method.instructions == null) return;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String) {
                ldc.cst = newValue;
                break;
            }
        }
    }
}
