package dev.frost.obfuscator.jni.core.ir;

import dev.frost.obfuscator.jni.core.model.ClassModel;
import dev.frost.obfuscator.jni.core.model.InstructionModel;
import dev.frost.obfuscator.jni.core.model.LabelModel;
import dev.frost.obfuscator.jni.core.model.MethodModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Lowers parsed class models into FrostJNI IR.
 */
public final class ClassToIRConverter {
    public IRClass convert(ClassModel classModel) {
        List<IRMethod> methods = classModel.methods().stream()
                .map(this::convertMethod)
                .toList();
        return new IRClass(classModel.internalName(), classModel.superName(), classModel.access(), methods);
    }

    private IRMethod convertMethod(MethodModel method) {
        List<IRInstruction> instructions = method.instructions().stream()
                .map(this::convertInstruction)
                .toList();
        return new IRMethod(
                method.ownerInternalName(),
                method.name(),
                method.descriptor(),
                method.access(),
                instructions,
                buildBlocks(instructions)
        );
    }

    private IRInstruction convertInstruction(InstructionModel instruction) {
        IROpcode opcode = toIrOpcode(instruction.mnemonic());
        if (opcode == IROpcode.UNKNOWN) {
            List<Object> operands = new ArrayList<>();
            operands.add(instruction.mnemonic());
            operands.addAll(instruction.operands());
            return new IRInstruction(opcode, operands);
        }
        return new IRInstruction(opcode, instruction.operands());
    }

    private List<IRBlock> buildBlocks(List<IRInstruction> instructions) {
        List<IRBlock> blocks = new ArrayList<>();
        LabelModel currentLabel = new LabelModel("entry");
        List<IRInstruction> current = new ArrayList<>();
        for (IRInstruction instruction : instructions) {
            if (instruction.opcode() == IROpcode.LABEL) {
                if (!current.isEmpty()) {
                    blocks.add(new IRBlock(currentLabel, current));
                    current = new ArrayList<>();
                }
                currentLabel = (LabelModel) instruction.operands().get(0);
            }
            current.add(instruction);
        }
        blocks.add(new IRBlock(currentLabel, current));
        return blocks;
    }

    private IROpcode toIrOpcode(String mnemonic) {
        return switch (mnemonic) {
            case "LABEL" -> IROpcode.LABEL;
            case "ACONST_NULL" -> IROpcode.ACONST_NULL;
            case "ICONST_M1", "ICONST_0", "ICONST_1", "ICONST_2", "ICONST_3", "ICONST_4", "ICONST_5" -> IROpcode.ICONST;
            case "LCONST_0", "LCONST_1" -> IROpcode.LDC;
            case "FCONST_0", "FCONST_1", "FCONST_2" -> IROpcode.LDC;
            case "DCONST_0", "DCONST_1" -> IROpcode.LDC;
            case "BIPUSH" -> IROpcode.BIPUSH;
            case "SIPUSH" -> IROpcode.SIPUSH;
            case "LDC" -> IROpcode.LDC;
            case "ILOAD" -> IROpcode.ILOAD;
            case "ISTORE" -> IROpcode.ISTORE;
            case "ALOAD" -> IROpcode.ALOAD;
            case "ASTORE" -> IROpcode.ASTORE;
            case "LLOAD" -> IROpcode.LLOAD;
            case "LSTORE" -> IROpcode.LSTORE;
            case "FLOAD" -> IROpcode.FLOAD;
            case "FSTORE" -> IROpcode.FSTORE;
            case "DLOAD" -> IROpcode.DLOAD;
            case "DSTORE" -> IROpcode.DSTORE;
            case "IINC" -> IROpcode.IINC;
            case "IADD" -> IROpcode.IADD;
            case "ISUB" -> IROpcode.ISUB;
            case "IMUL" -> IROpcode.IMUL;
            case "IDIV" -> IROpcode.IDIV;
            case "IREM" -> IROpcode.IREM;
            case "INEG" -> IROpcode.INEG;
            case "LADD" -> IROpcode.LADD;
            case "LSUB" -> IROpcode.LSUB;
            case "LMUL" -> IROpcode.LMUL;
            case "LDIV" -> IROpcode.LDIV;
            case "LREM" -> IROpcode.LREM;
            case "LNEG" -> IROpcode.LNEG;
            case "FADD" -> IROpcode.FADD;
            case "FSUB" -> IROpcode.FSUB;
            case "FMUL" -> IROpcode.FMUL;
            case "FDIV" -> IROpcode.FDIV;
            case "FREM" -> IROpcode.FREM;
            case "FNEG" -> IROpcode.FNEG;
            case "DADD" -> IROpcode.DADD;
            case "DSUB" -> IROpcode.DSUB;
            case "DMUL" -> IROpcode.DMUL;
            case "DDIV" -> IROpcode.DDIV;
            case "DREM" -> IROpcode.DREM;
            case "DNEG" -> IROpcode.DNEG;
            case "ISHL" -> IROpcode.ISHL;
            case "ISHR" -> IROpcode.ISHR;
            case "IUSHR" -> IROpcode.IUSHR;
            case "IAND" -> IROpcode.IAND;
            case "IOR" -> IROpcode.IOR;
            case "IXOR" -> IROpcode.IXOR;
            case "LSHL" -> IROpcode.LSHL;
            case "LSHR" -> IROpcode.LSHR;
            case "LUSHR" -> IROpcode.LUSHR;
            case "LAND" -> IROpcode.LAND;
            case "LOR" -> IROpcode.LOR;
            case "LXOR" -> IROpcode.LXOR;
            case "LCMP" -> IROpcode.LCMP;
            case "FCMPL" -> IROpcode.FCMPL;
            case "FCMPG" -> IROpcode.FCMPG;
            case "DCMPL" -> IROpcode.DCMPL;
            case "DCMPG" -> IROpcode.DCMPG;
            case "I2L" -> IROpcode.I2L;
            case "I2F" -> IROpcode.I2F;
            case "I2D" -> IROpcode.I2D;
            case "L2I" -> IROpcode.L2I;
            case "L2F" -> IROpcode.L2F;
            case "L2D" -> IROpcode.L2D;
            case "F2I" -> IROpcode.F2I;
            case "F2L" -> IROpcode.F2L;
            case "F2D" -> IROpcode.F2D;
            case "D2I" -> IROpcode.D2I;
            case "D2L" -> IROpcode.D2L;
            case "D2F" -> IROpcode.D2F;
            case "I2B" -> IROpcode.I2B;
            case "I2C" -> IROpcode.I2C;
            case "I2S" -> IROpcode.I2S;
            case "IRETURN" -> IROpcode.IRETURN;
            case "LRETURN" -> IROpcode.LRETURN;
            case "FRETURN" -> IROpcode.FRETURN;
            case "DRETURN" -> IROpcode.DRETURN;
            case "ARETURN" -> IROpcode.ARETURN;
            case "RETURN" -> IROpcode.RETURN;
            case "POP" -> IROpcode.POP;
            case "POP2" -> IROpcode.POP2;
            case "DUP" -> IROpcode.DUP;
            case "DUP_X1" -> IROpcode.DUP_X1;
            case "DUP_X2" -> IROpcode.DUP_X2;
            case "DUP2" -> IROpcode.DUP2;
            case "DUP2_X1" -> IROpcode.DUP2_X1;
            case "DUP2_X2" -> IROpcode.DUP2_X2;
            case "SWAP" -> IROpcode.SWAP;
            case "CHECKCAST" -> IROpcode.CHECKCAST;
            case "INSTANCEOF" -> IROpcode.INSTANCEOF;
            case "NEW" -> IROpcode.NEW;
            case "ANEWARRAY" -> IROpcode.ANEWARRAY;
            case "NEWARRAY" -> IROpcode.NEWARRAY;
            case "MULTIANEWARRAY" -> IROpcode.MULTIANEWARRAY;
            case "IALOAD" -> IROpcode.IALOAD;
            case "LALOAD" -> IROpcode.LALOAD;
            case "FALOAD" -> IROpcode.FALOAD;
            case "DALOAD" -> IROpcode.DALOAD;
            case "AALOAD" -> IROpcode.AALOAD;
            case "BALOAD" -> IROpcode.BALOAD;
            case "CALOAD" -> IROpcode.CALOAD;
            case "SALOAD" -> IROpcode.SALOAD;
            case "IASTORE" -> IROpcode.IASTORE;
            case "LASTORE" -> IROpcode.LASTORE;
            case "FASTORE" -> IROpcode.FASTORE;
            case "DASTORE" -> IROpcode.DASTORE;
            case "AASTORE" -> IROpcode.AASTORE;
            case "BASTORE" -> IROpcode.BASTORE;
            case "CASTORE" -> IROpcode.CASTORE;
            case "SASTORE" -> IROpcode.SASTORE;
            case "ARRAYLENGTH" -> IROpcode.ARRAYLENGTH;
            case "IFEQ" -> IROpcode.IFEQ;
            case "IFNE" -> IROpcode.IFNE;
            case "IFLT" -> IROpcode.IFLT;
            case "IFGE" -> IROpcode.IFGE;
            case "IFGT" -> IROpcode.IFGT;
            case "IFLE" -> IROpcode.IFLE;
            case "IF_ICMPEQ" -> IROpcode.IF_ICMPEQ;
            case "IF_ICMPNE" -> IROpcode.IF_ICMPNE;
            case "IF_ICMPLT" -> IROpcode.IF_ICMPLT;
            case "IF_ICMPGE" -> IROpcode.IF_ICMPGE;
            case "IF_ICMPGT" -> IROpcode.IF_ICMPGT;
            case "IF_ICMPLE" -> IROpcode.IF_ICMPLE;
            case "IF_ACMPEQ" -> IROpcode.IF_ACMPEQ;
            case "IF_ACMPNE" -> IROpcode.IF_ACMPNE;
            case "IFNULL" -> IROpcode.IFNULL;
            case "IFNONNULL" -> IROpcode.IFNONNULL;
            case "GOTO" -> IROpcode.GOTO;
            case "TABLESWITCH" -> IROpcode.TABLESWITCH;
            case "LOOKUPSWITCH" -> IROpcode.LOOKUPSWITCH;
            case "ATHROW" -> IROpcode.ATHROW;
            case "MONITORENTER" -> IROpcode.MONITORENTER;
            case "MONITOREXIT" -> IROpcode.MONITOREXIT;
            case "INVOKESTATIC" -> IROpcode.INVOKESTATIC;
            case "INVOKEVIRTUAL" -> IROpcode.INVOKEVIRTUAL;
            case "INVOKESPECIAL" -> IROpcode.INVOKESPECIAL;
            case "INVOKEINTERFACE" -> IROpcode.INVOKEINTERFACE;
            case "INVOKEDYNAMIC" -> IROpcode.INVOKEDYNAMIC;
            case "GETSTATIC" -> IROpcode.GETSTATIC;
            case "PUTSTATIC" -> IROpcode.PUTSTATIC;
            case "GETFIELD" -> IROpcode.GETFIELD;
            case "PUTFIELD" -> IROpcode.PUTFIELD;
            default -> IROpcode.UNKNOWN;
        };
    }
}


