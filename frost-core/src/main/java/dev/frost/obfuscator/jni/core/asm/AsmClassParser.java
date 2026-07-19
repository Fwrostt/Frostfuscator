package dev.frost.obfuscator.jni.core.asm;

import dev.frost.obfuscator.jni.core.model.ClassModel;
import dev.frost.obfuscator.jni.core.model.DynamicInvocationReference;
import dev.frost.obfuscator.jni.core.model.FieldReference;
import dev.frost.obfuscator.jni.core.model.InstructionKind;
import dev.frost.obfuscator.jni.core.model.InstructionModel;
import dev.frost.obfuscator.jni.core.model.LabelModel;
import dev.frost.obfuscator.jni.core.model.MethodModel;
import dev.frost.obfuscator.jni.core.model.MethodReference;
import dev.frost.obfuscator.jni.core.model.SwitchModel;
import dev.frost.obfuscator.jni.core.model.TryCatchModel;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses JVM class files with ASM and immediately converts mutable ASM nodes to
 * FrostJNI model objects.
 */
public final class AsmClassParser {
    public ClassModel parse(Path classFile) throws IOException {
        try (InputStream inputStream = Files.newInputStream(classFile)) {
            return parse(inputStream);
        }
    }

    public ClassModel parse(InputStream inputStream) throws IOException {
        ClassReader reader = new ClassReader(inputStream);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        return parseNode(node);
    }

    public ClassModel parse(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        return parseNode(node);
    }

    public ClassModel parseNode(ClassNode node) {
        List<MethodModel> methods = node.methods.stream()
                .map(method -> parseMethod(node.name, method))
                .toList();
        return new ClassModel(node.name, node.superName, node.access, methods, annotationsOf(node.visibleAnnotations, node.invisibleAnnotations));
    }

    private MethodModel parseMethod(String ownerInternalName, MethodNode method) {
        Map<LabelNode, LabelModel> labels = new IdentityHashMap<>();
        List<InstructionModel> instructions = new ArrayList<>();

        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LabelNode labelNode) {
                instructions.add(InstructionModel.label(labelFor(labels, labelNode)));
                continue;
            }
            if (instruction.getOpcode() < 0) {
                continue;
            }
            instructions.add(parseInstruction(instruction, labels));
        }

        List<TryCatchModel> tryCatchBlocks = method.tryCatchBlocks.stream()
                .map(block -> parseTryCatch(block, labels))
                .toList();
        return new MethodModel(
                ownerInternalName,
                method.name,
                method.desc,
                method.access,
                instructions,
                tryCatchBlocks,
                annotationsOf(method.visibleAnnotations, method.invisibleAnnotations)
        );
    }

    private InstructionModel parseInstruction(AbstractInsnNode instruction, Map<LabelNode, LabelModel> labels) {
        int opcode = instruction.getOpcode();
        String mnemonic = OpcodeNames.nameOf(opcode);
        return switch (instruction) {
            case InsnNode ignored -> new InstructionModel(opcode, mnemonic, kindFor(opcode), implicitOperands(opcode));
            case IntInsnNode intInsn -> new InstructionModel(opcode, mnemonic, InstructionKind.CONSTANT, List.of(intInsn.operand));
            case LdcInsnNode ldcInsn -> new InstructionModel(opcode, mnemonic, InstructionKind.CONSTANT, List.of(ldcInsn.cst));
            case VarInsnNode varInsn -> new InstructionModel(opcode, mnemonic, InstructionKind.VARIABLE, List.of(varInsn.var));
            case IincInsnNode iincInsn -> new InstructionModel(opcode, mnemonic, InstructionKind.VARIABLE, List.of(iincInsn.var, iincInsn.incr));
            case JumpInsnNode jumpInsn -> new InstructionModel(opcode, mnemonic, InstructionKind.BRANCH, List.of(labelFor(labels, jumpInsn.label)));
            case TypeInsnNode typeInsn -> new InstructionModel(opcode, mnemonic, InstructionKind.OTHER, List.of(typeInsn.desc));
            case MultiANewArrayInsnNode multiArrayInsn -> new InstructionModel(opcode, mnemonic, InstructionKind.OTHER, List.of(multiArrayInsn.desc, multiArrayInsn.dims));
            case TableSwitchInsnNode tableSwitchInsn -> new InstructionModel(opcode, mnemonic, InstructionKind.BRANCH, List.of(parseTableSwitch(tableSwitchInsn, labels)));
            case LookupSwitchInsnNode lookupSwitchInsn -> new InstructionModel(opcode, mnemonic, InstructionKind.BRANCH, List.of(parseLookupSwitch(lookupSwitchInsn, labels)));
            case InvokeDynamicInsnNode dynamicInsn -> new InstructionModel(opcode, mnemonic, InstructionKind.METHOD_CALL, List.of(parseDynamic(dynamicInsn)));
            case MethodInsnNode methodInsn -> new InstructionModel(opcode, mnemonic, InstructionKind.METHOD_CALL, List.of(new MethodReference(
                    methodInsn.owner,
                    methodInsn.name,
                    methodInsn.desc,
                    methodInsn.itf
            )));
            case FieldInsnNode fieldInsn -> new InstructionModel(opcode, mnemonic, InstructionKind.FIELD_ACCESS, List.of(new FieldReference(
                    fieldInsn.owner,
                    fieldInsn.name,
                    fieldInsn.desc
            )));
            default -> new InstructionModel(opcode, mnemonic, InstructionKind.OTHER, List.of());
        };
    }

    private DynamicInvocationReference parseDynamic(InvokeDynamicInsnNode instruction) {
        return new DynamicInvocationReference(
                instruction.name,
                instruction.desc,
                instruction.bsm == null ? "" : instruction.bsm.getOwner(),
                instruction.bsm == null ? "" : instruction.bsm.getName(),
                instruction.bsm == null ? "" : instruction.bsm.getDesc(),
                bootstrapArguments(instruction.bsmArgs)
        );
    }

    private List<Object> bootstrapArguments(Object[] arguments) {
        List<Object> values = new ArrayList<>();
        if (arguments == null) {
            return values;
        }
        for (Object argument : arguments) {
            values.add(bootstrapArgument(argument));
        }
        return values;
    }

    private Object bootstrapArgument(Object argument) {
        if (argument instanceof Type type) {
            return type.getDescriptor();
        }
        if (argument instanceof Handle handle) {
            return handle.getOwner() + "." + handle.getName() + handle.getDesc();
        }
        return argument;
    }

    private List<Object> implicitOperands(int opcode) {
        return switch (opcode) {
            case Opcodes.ICONST_M1 -> List.of(-1);
            case Opcodes.ICONST_0 -> List.of(0);
            case Opcodes.ICONST_1 -> List.of(1);
            case Opcodes.ICONST_2 -> List.of(2);
            case Opcodes.ICONST_3 -> List.of(3);
            case Opcodes.ICONST_4 -> List.of(4);
            case Opcodes.ICONST_5 -> List.of(5);
            case Opcodes.ACONST_NULL -> List.of(0);
            case Opcodes.LCONST_0 -> List.of(0L);
            case Opcodes.LCONST_1 -> List.of(1L);
            case Opcodes.FCONST_0 -> List.of(0.0f);
            case Opcodes.FCONST_1 -> List.of(1.0f);
            case Opcodes.FCONST_2 -> List.of(2.0f);
            case Opcodes.DCONST_0 -> List.of(0.0d);
            case Opcodes.DCONST_1 -> List.of(1.0d);
            default -> List.of();
        };
    }

    private InstructionKind kindFor(int opcode) {
        return switch (opcode) {
            case Opcodes.ACONST_NULL, Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                    Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.LCONST_0, Opcodes.LCONST_1,
                    Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2, Opcodes.DCONST_0, Opcodes.DCONST_1 -> InstructionKind.CONSTANT;
            case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM, Opcodes.INEG,
                    Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM, Opcodes.LNEG,
                    Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM, Opcodes.FNEG,
                    Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM, Opcodes.DNEG -> InstructionKind.ARITHMETIC;
            case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN, Opcodes.RETURN -> InstructionKind.RETURN;
            default -> InstructionKind.OTHER;
        };
    }

    private SwitchModel parseTableSwitch(TableSwitchInsnNode instruction, Map<LabelNode, LabelModel> labels) {
        List<Integer> keys = new ArrayList<>();
        for (int key = instruction.min; key <= instruction.max; key++) {
            keys.add(key);
        }
        return new SwitchModel(
                labelFor(labels, instruction.dflt),
                keys,
                instruction.labels.stream().map(label -> labelFor(labels, label)).toList()
        );
    }

    private SwitchModel parseLookupSwitch(LookupSwitchInsnNode instruction, Map<LabelNode, LabelModel> labels) {
        return new SwitchModel(
                labelFor(labels, instruction.dflt),
                List.copyOf(instruction.keys),
                instruction.labels.stream().map(label -> labelFor(labels, label)).toList()
        );
    }

    private TryCatchModel parseTryCatch(TryCatchBlockNode block, Map<LabelNode, LabelModel> labels) {
        return new TryCatchModel(
                labelFor(labels, block.start),
                labelFor(labels, block.end),
                labelFor(labels, block.handler),
                block.type
        );
    }

    private LabelModel labelFor(Map<LabelNode, LabelModel> labels, LabelNode node) {
        return labels.computeIfAbsent(node, ignored -> new LabelModel("L" + labels.size()));
    }

    private List<String> annotationsOf(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<String> descriptors = new ArrayList<>();
        if (visible != null) {
            visible.stream().map(annotation -> annotation.desc).forEach(descriptors::add);
        }
        if (invisible != null) {
            invisible.stream().map(annotation -> annotation.desc).forEach(descriptors::add);
        }
        return descriptors;
    }
}


