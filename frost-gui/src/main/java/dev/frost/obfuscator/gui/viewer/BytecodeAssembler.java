package dev.frost.obfuscator.gui.viewer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Printer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles text-represented ASM bytecode back into a JVM ClassNode / byte array.
 * Supports modifying string constants directly from ASM Textifier output.
 */
public final class BytecodeAssembler {

    private static final Map<String, Integer> OPCODES_BY_NAME = opcodeNames();

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

            Map<String, MethodInstructions> methods = indexMethods(classNode);
            MethodInstructions currentMethod = null;

            for (String line : textDisassembly.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                    continue;
                }

                MethodInstructions declaredMethod = findDeclaredMethod(trimmed, methods);
                if (declaredMethod != null) {
                    if (currentMethod != null) {
                        currentMethod.verifyComplete();
                    }
                    currentMethod = declaredMethod;
                    currentMethod.reset();
                    continue;
                }

                if (currentMethod == null) {
                    continue;
                }

                String mnemonic = firstToken(trimmed);
                Integer opcode = OPCODES_BY_NAME.get(mnemonic);
                if (opcode == null) {
                    continue;
                }

                AbstractInsnNode target = currentMethod.nextInstruction();
                if (target == null) {
                    throw new IllegalArgumentException("Method " + currentMethod.displayName()
                            + " contains more instructions than the original bytecode");
                }
                if (target.getOpcode() != opcode) {
                    throw new IllegalArgumentException("Instruction structure changed in method "
                            + currentMethod.displayName() + " at instruction " + currentMethod.position()
                            + ": expected " + Printer.OPCODES[target.getOpcode()] + " but found " + mnemonic);
                }

                if (target instanceof LdcInsnNode ldc && ldc.cst instanceof String) {
                    ldc.cst = parseStringConstant(trimmed.substring(mnemonic.length()).trim(),
                            currentMethod.displayName(), currentMethod.position());
                }
            }

            if (currentMethod != null) {
                currentMethod.verifyComplete();
            }

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            classNode.accept(writer);
            return new AssemblyResult(true, writer.toByteArray(), null);
        } catch (Exception exception) {
            return new AssemblyResult(false, null, "Assembly failed: " + exception.getMessage());
        }
    }

    private static Map<String, MethodInstructions> indexMethods(ClassNode classNode) {
        Map<String, MethodInstructions> methods = new LinkedHashMap<>();
        for (MethodNode method : classNode.methods) {
            List<AbstractInsnNode> instructions = new ArrayList<>();
            if (method.instructions != null) {
                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                     instruction = instruction.getNext()) {
                    if (instruction.getOpcode() >= 0) {
                        instructions.add(instruction);
                    }
                }
            }
            methods.put(method.name + method.desc,
                    new MethodInstructions(method.name, method.desc, instructions));
        }
        return methods;
    }

    private static MethodInstructions findDeclaredMethod(
            String line, Map<String, MethodInstructions> methods) {
        for (Map.Entry<String, MethodInstructions> entry : methods.entrySet()) {
            String signature = entry.getKey();
            int index = line.indexOf(signature);
            if (index >= 0
                    && (index == 0 || Character.isWhitespace(line.charAt(index - 1)))
                    && (index + signature.length() == line.length()
                    || Character.isWhitespace(line.charAt(index + signature.length())))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String firstToken(String line) {
        int space = line.indexOf(' ');
        return space < 0 ? line : line.substring(0, space);
    }

    private static String parseStringConstant(String token, String method, int position) {
        if (token.length() < 2 || token.charAt(0) != '"' || token.charAt(token.length() - 1) != '"') {
            throw new IllegalArgumentException("Expected a quoted string for LDC in method " + method
                    + " at instruction " + position);
        }

        StringBuilder value = new StringBuilder(token.length() - 2);
        for (int index = 1; index < token.length() - 1; index++) {
            char current = token.charAt(index);
            if (current != '\\') {
                value.append(current);
                continue;
            }
            if (++index >= token.length() - 1) {
                throw invalidEscape(method, position);
            }
            char escaped = token.charAt(index);
            switch (escaped) {
                case 'b' -> value.append('\b');
                case 't' -> value.append('\t');
                case 'n' -> value.append('\n');
                case 'f' -> value.append('\f');
                case 'r' -> value.append('\r');
                case '"' -> value.append('"');
                case '\\' -> value.append('\\');
                case 'u' -> {
                    if (index + 4 >= token.length()) {
                        throw invalidEscape(method, position);
                    }
                    String hex = token.substring(index + 1, index + 5);
                    try {
                        value.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException exception) {
                        throw invalidEscape(method, position);
                    }
                    index += 4;
                }
                default -> throw invalidEscape(method, position);
            }
        }
        return value.toString();
    }

    private static IllegalArgumentException invalidEscape(String method, int position) {
        return new IllegalArgumentException("Invalid string escape for LDC in method " + method
                + " at instruction " + position);
    }

    private static Map<String, Integer> opcodeNames() {
        Map<String, Integer> names = new HashMap<>();
        for (int opcode = 0; opcode < Printer.OPCODES.length; opcode++) {
            String name = Printer.OPCODES[opcode];
            if (name != null) {
                names.put(name, opcode);
            }
        }
        return Map.copyOf(names);
    }

    private static final class MethodInstructions {
        private final String name;
        private final String descriptor;
        private final List<AbstractInsnNode> instructions;
        private int cursor;

        private MethodInstructions(String name, String descriptor, List<AbstractInsnNode> instructions) {
            this.name = name;
            this.descriptor = descriptor;
            this.instructions = instructions;
        }

        private void reset() {
            cursor = 0;
        }

        private AbstractInsnNode nextInstruction() {
            return cursor < instructions.size() ? instructions.get(cursor++) : null;
        }

        private int position() {
            return cursor - 1;
        }

        private void verifyComplete() {
            if (cursor != instructions.size()) {
                throw new IllegalArgumentException("Method " + displayName() + " contains " + cursor
                        + " instructions in the edited text but " + instructions.size()
                        + " in the original bytecode");
            }
        }

        private String displayName() {
            return name + descriptor;
        }
    }
}
