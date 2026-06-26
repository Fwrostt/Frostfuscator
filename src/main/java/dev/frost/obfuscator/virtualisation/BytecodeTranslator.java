package dev.frost.obfuscator.virtualisation;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.ByteArrayOutputStream;
import java.util.*;

public class BytecodeTranslator {

    public static class TranslatedMethod {
        public final byte[] bytecode;
        public final Object[] constPool;
        public final int maxLocals;
        public final int maxStack;
        public final int[] argSlots;

        public TranslatedMethod(byte[] bytecode, Object[] constPool, int maxLocals, int maxStack, int[] argSlots) {
            this.bytecode = bytecode;
            this.constPool = constPool;
            this.maxLocals = maxLocals;
            this.maxStack = maxStack;
            this.argSlots = argSlots;
        }
    }

    private final MethodNode method;
    private final OpcodeTable opcodeTable;
    private final List<Object> constPool = new ArrayList<>();
    private final Map<LabelNode, Integer> labelPositions = new HashMap<>();

    public BytecodeTranslator(MethodNode method, OpcodeTable opcodeTable) {
        this.method = method;
        this.opcodeTable = opcodeTable;
    }

    public static boolean isEligible(MethodNode method) {
        if (!method.tryCatchBlocks.isEmpty()) {
            return false;
        }
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InvokeDynamicInsnNode) {
                return false;
            }
            if (insn instanceof MultiANewArrayInsnNode) {
                return false;
            }
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                return false;
            }
            if (insn.getOpcode() == Opcodes.JSR || insn.getOpcode() == Opcodes.RET) {
                return false;
            }
        }
        return true;
    }

    public TranslatedMethod translate() {
        // Pass 1: Compute absolute instruction offsets and record label positions
        int currentOffset = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LabelNode) {
                labelPositions.put((LabelNode) insn, currentOffset);
            } else {
                currentOffset += getInsnSize(insn);
            }
        }

        // Pass 2: Write custom bytecode
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode) {
                continue;
            }
            writeInsn(insn, out);
        }

        // Compute argSlots
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        Type[] argTypes = Type.getArgumentTypes(method.desc);
        int[] argSlots = new int[argTypes.length + (isStatic ? 0 : 1)];
        int nextSlot = 0;
        if (!isStatic) {
            argSlots[0] = 0;
            nextSlot = 1;
        }
        for (int i = 0; i < argTypes.length; i++) {
            argSlots[i + (isStatic ? 0 : 1)] = nextSlot;
            nextSlot += argTypes[i].getSize();
        }

        return new TranslatedMethod(
            out.toByteArray(),
            constPool.toArray(new Object[0]),
            method.maxLocals,
            Math.max(method.maxStack, 16),
            argSlots
        );
    }

    private int addConstant(Object val) {
        if (val instanceof Type) {
            val = new VirtualConstant.ClassRef(((Type) val).getInternalName());
        }
        int idx = constPool.indexOf(val);
        if (idx != -1) {
            return idx;
        }
        constPool.add(val);
        return constPool.size() - 1;
    }

    private int getInsnSize(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode == -1) return 0; // line numbers, frames, labels

        if (insn instanceof InsnNode) {
            return 1;
        } else if (insn instanceof IntInsnNode) {
            if (opcode == Opcodes.BIPUSH || opcode == Opcodes.NEWARRAY) {
                return 2;
            } else if (opcode == Opcodes.SIPUSH) {
                return 3;
            }
        } else if (insn instanceof VarInsnNode) {
            return 3;
        } else if (insn instanceof LdcInsnNode) {
            return 3;
        } else if (insn instanceof FieldInsnNode) {
            return 3;
        } else if (insn instanceof MethodInsnNode) {
            return 3;
        } else if (insn instanceof TypeInsnNode) {
            return 3;
        } else if (insn instanceof IincInsnNode) {
            return 5;
        } else if (insn instanceof JumpInsnNode) {
            return 5;
        } else if (insn instanceof TableSwitchInsnNode) {
            TableSwitchInsnNode tsin = (TableSwitchInsnNode) insn;
            int numTargets = tsin.max - tsin.min + 1;
            return 1 + 4 + 4 + 4 + numTargets * 4;
        } else if (insn instanceof LookupSwitchInsnNode) {
            LookupSwitchInsnNode lsin = (LookupSwitchInsnNode) insn;
            return 1 + 4 + 4 + lsin.keys.size() * 8;
        }

        throw new UnsupportedOperationException("Unsupported instruction type: " + insn.getClass().getName());
    }

    private void writeInsn(AbstractInsnNode insn, ByteArrayOutputStream out) {
        int opcode = insn.getOpcode();
        if (insn instanceof InsnNode) {
            int internalOp = mapInsnOpcode(opcode);
            out.write(opcodeTable.encode(internalOp));
        } else if (insn instanceof IntInsnNode) {
            int internalOp = mapIntOpcode(opcode);
            out.write(opcodeTable.encode(internalOp));
            int operand = ((IntInsnNode) insn).operand;
            if (opcode == Opcodes.BIPUSH || opcode == Opcodes.NEWARRAY) {
                out.write(operand & 0xFF);
            } else {
                out.write((operand >> 8) & 0xFF);
                out.write(operand & 0xFF);
            }
        } else if (insn instanceof VarInsnNode) {
            int internalOp = mapVarOpcode(opcode);
            out.write(opcodeTable.encode(internalOp));
            int varIdx = ((VarInsnNode) insn).var;
            out.write((varIdx >> 8) & 0xFF);
            out.write(varIdx & 0xFF);
        } else if (insn instanceof LdcInsnNode) {
            // OP_LDC
            out.write(opcodeTable.encode(18));
            Object cst = ((LdcInsnNode) insn).cst;
            int cpIdx = addConstant(cst);
            out.write((cpIdx >> 8) & 0xFF);
            out.write(cpIdx & 0xFF);
        } else if (insn instanceof FieldInsnNode) {
            int internalOp = mapFieldOpcode(opcode);
            out.write(opcodeTable.encode(internalOp));
            FieldInsnNode fin = (FieldInsnNode) insn;
            int cpIdx = addConstant(new VirtualConstant.FieldRef(fin.owner, fin.name, fin.desc));
            out.write((cpIdx >> 8) & 0xFF);
            out.write(cpIdx & 0xFF);
        } else if (insn instanceof MethodInsnNode) {
            int internalOp = mapMethodOpcode(opcode);
            out.write(opcodeTable.encode(internalOp));
            MethodInsnNode min = (MethodInsnNode) insn;
            int cpIdx = addConstant(new VirtualConstant.MethodRef(min.owner, min.name, min.desc, min.itf));
            out.write((cpIdx >> 8) & 0xFF);
            out.write(cpIdx & 0xFF);
        } else if (insn instanceof TypeInsnNode) {
            int internalOp = mapTypeOpcode(opcode);
            out.write(opcodeTable.encode(internalOp));
            TypeInsnNode tin = (TypeInsnNode) insn;
            int cpIdx = addConstant(tin.desc); // class name as string
            out.write((cpIdx >> 8) & 0xFF);
            out.write(cpIdx & 0xFF);
        } else if (insn instanceof IincInsnNode) {
            // OP_IINC
            out.write(opcodeTable.encode(82));
            IincInsnNode iin = (IincInsnNode) insn;
            out.write((iin.var >> 8) & 0xFF);
            out.write(iin.var & 0xFF);
            out.write((iin.incr >> 8) & 0xFF);
            out.write(iin.incr & 0xFF);
        } else if (insn instanceof JumpInsnNode) {
            int internalOp = mapJumpOpcode(opcode);
            out.write(opcodeTable.encode(internalOp));
            JumpInsnNode jin = (JumpInsnNode) insn;
            int targetPos = labelPositions.get(jin.label);
            out.write((targetPos >> 24) & 0xFF);
            out.write((targetPos >> 16) & 0xFF);
            out.write((targetPos >> 8) & 0xFF);
            out.write(targetPos & 0xFF);
        } else if (insn instanceof TableSwitchInsnNode) {
            // OP_TABLESWITCH
            out.write(opcodeTable.encode(118));
            TableSwitchInsnNode tsin = (TableSwitchInsnNode) insn;
            int defaultTarget = labelPositions.get(tsin.dflt);
            writeFourBytes(out, defaultTarget);
            writeFourBytes(out, tsin.min);
            writeFourBytes(out, tsin.max);
            for (LabelNode label : tsin.labels) {
                writeFourBytes(out, labelPositions.get(label));
            }
        } else if (insn instanceof LookupSwitchInsnNode) {
            // OP_LOOKUPSWITCH
            out.write(opcodeTable.encode(119));
            LookupSwitchInsnNode lsin = (LookupSwitchInsnNode) insn;
            int defaultTarget = labelPositions.get(lsin.dflt);
            writeFourBytes(out, defaultTarget);
            writeFourBytes(out, lsin.keys.size());
            for (int i = 0; i < lsin.keys.size(); i++) {
                writeFourBytes(out, (Integer) lsin.keys.get(i));
                writeFourBytes(out, labelPositions.get(lsin.labels.get(i)));
            }
        } else {
            throw new UnsupportedOperationException("Cannot write unsupported instruction: " + insn.getClass().getName());
        }
    }

    private void writeFourBytes(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private int mapInsnOpcode(int opcode) {
        switch (opcode) {
            case Opcodes.NOP: return 0;
            case Opcodes.ACONST_NULL: return 1;
            case Opcodes.ICONST_M1: return 2;
            case Opcodes.ICONST_0: return 3;
            case Opcodes.ICONST_1: return 4;
            case Opcodes.ICONST_2: return 5;
            case Opcodes.ICONST_3: return 6;
            case Opcodes.ICONST_4: return 7;
            case Opcodes.ICONST_5: return 8;
            case Opcodes.LCONST_0: return 9;
            case Opcodes.LCONST_1: return 10;
            case Opcodes.FCONST_0: return 11;
            case Opcodes.FCONST_1: return 12;
            case Opcodes.FCONST_2: return 13;
            case Opcodes.DCONST_0: return 14;
            case Opcodes.DCONST_1: return 15;
            case Opcodes.IALOAD: return 21;
            case Opcodes.LALOAD: return 22;
            case Opcodes.FALOAD: return 23;
            case Opcodes.DALOAD: return 24;
            case Opcodes.AALOAD: return 25;
            case Opcodes.BALOAD: return 26;
            case Opcodes.CALOAD: return 27;
            case Opcodes.SALOAD: return 28;
            case Opcodes.IASTORE: return 29;
            case Opcodes.LASTORE: return 30;
            case Opcodes.FASTORE: return 31;
            case Opcodes.DASTORE: return 32;
            case Opcodes.AASTORE: return 33;
            case Opcodes.BASTORE: return 34;
            case Opcodes.CASTORE: return 35;
            case Opcodes.SASTORE: return 36;
            case Opcodes.POP: return 37;
            case Opcodes.POP2: return 38;
            case Opcodes.DUP: return 39;
            case Opcodes.DUP_X1: return 40;
            case Opcodes.DUP_X2: return 41;
            case Opcodes.DUP2: return 42;
            case Opcodes.DUP2_X1: return 43;
            case Opcodes.DUP2_X2: return 44;
            case Opcodes.SWAP: return 45;
            case Opcodes.IADD: return 46;
            case Opcodes.LADD: return 47;
            case Opcodes.FADD: return 48;
            case Opcodes.DADD: return 49;
            case Opcodes.ISUB: return 50;
            case Opcodes.LSUB: return 51;
            case Opcodes.FSUB: return 52;
            case Opcodes.DSUB: return 53;
            case Opcodes.IMUL: return 54;
            case Opcodes.LMUL: return 55;
            case Opcodes.FMUL: return 56;
            case Opcodes.DMUL: return 57;
            case Opcodes.IDIV: return 58;
            case Opcodes.LDIV: return 59;
            case Opcodes.FDIV: return 60;
            case Opcodes.DDIV: return 61;
            case Opcodes.IREM: return 62;
            case Opcodes.LREM: return 63;
            case Opcodes.FREM: return 64;
            case Opcodes.DREM: return 65;
            case Opcodes.INEG: return 66;
            case Opcodes.LNEG: return 67;
            case Opcodes.FNEG: return 68;
            case Opcodes.DNEG: return 69;
            case Opcodes.ISHL: return 70;
            case Opcodes.LSHL: return 71;
            case Opcodes.ISHR: return 72;
            case Opcodes.LSHR: return 73;
            case Opcodes.IUSHR: return 74;
            case Opcodes.LUSHR: return 75;
            case Opcodes.IAND: return 76;
            case Opcodes.LAND: return 77;
            case Opcodes.IOR: return 78;
            case Opcodes.LOR: return 79;
            case Opcodes.IXOR: return 80;
            case Opcodes.LXOR: return 81;
            case Opcodes.I2L: return 83;
            case Opcodes.I2F: return 84;
            case Opcodes.I2D: return 85;
            case Opcodes.L2I: return 86;
            case Opcodes.L2F: return 87;
            case Opcodes.L2D: return 88;
            case Opcodes.F2I: return 89;
            case Opcodes.F2L: return 90;
            case Opcodes.F2D: return 91;
            case Opcodes.D2I: return 92;
            case Opcodes.D2L: return 93;
            case Opcodes.D2F: return 94;
            case Opcodes.I2B: return 95;
            case Opcodes.I2C: return 96;
            case Opcodes.I2S: return 97;
            case Opcodes.LCMP: return 98;
            case Opcodes.FCMPL: return 99;
            case Opcodes.FCMPG: return 100;
            case Opcodes.DCMPL: return 101;
            case Opcodes.DCMPG: return 102;
            case Opcodes.IRETURN: return 120;
            case Opcodes.LRETURN: return 121;
            case Opcodes.FRETURN: return 122;
            case Opcodes.DRETURN: return 123;
            case Opcodes.ARETURN: return 124;
            case Opcodes.RETURN: return 125;
            case Opcodes.ARRAYLENGTH: return 137;
            case Opcodes.ATHROW: return 138;
            case Opcodes.MONITORENTER: return 141;
            case Opcodes.MONITOREXIT: return 142;
            default:
                throw new IllegalArgumentException("Unknown InsnNode opcode: " + opcode);
        }
    }

    private int mapIntOpcode(int opcode) {
        switch (opcode) {
            case Opcodes.BIPUSH: return 16;
            case Opcodes.SIPUSH: return 17;
            case Opcodes.NEWARRAY: return 135;
            default:
                throw new IllegalArgumentException("Unknown IntInsnNode opcode: " + opcode);
        }
    }

    private int mapVarOpcode(int opcode) {
        switch (opcode) {
            case Opcodes.ILOAD:
            case Opcodes.FLOAD:
            case Opcodes.ALOAD:
                return 19;
            case Opcodes.LLOAD:
            case Opcodes.DLOAD:
                return 146;
            case Opcodes.ISTORE:
            case Opcodes.FSTORE:
            case Opcodes.ASTORE:
                return 20;
            case Opcodes.LSTORE:
            case Opcodes.DSTORE:
                return 147;
            default:
                throw new IllegalArgumentException("Unknown VarInsnNode opcode: " + opcode);
        }
    }

    private int mapFieldOpcode(int opcode) {
        switch (opcode) {
            case Opcodes.GETSTATIC: return 126;
            case Opcodes.PUTSTATIC: return 127;
            case Opcodes.GETFIELD: return 128;
            case Opcodes.PUTFIELD: return 129;
            default:
                throw new IllegalArgumentException("Unknown FieldInsnNode opcode: " + opcode);
        }
    }

    private int mapMethodOpcode(int opcode) {
        switch (opcode) {
            case Opcodes.INVOKEVIRTUAL: return 130;
            case Opcodes.INVOKESPECIAL: return 131;
            case Opcodes.INVOKESTATIC: return 132;
            case Opcodes.INVOKEINTERFACE: return 133;
            default:
                throw new IllegalArgumentException("Unknown MethodInsnNode opcode: " + opcode);
        }
    }

    private int mapTypeOpcode(int opcode) {
        switch (opcode) {
            case Opcodes.NEW: return 134;
            case Opcodes.ANEWARRAY: return 136;
            case Opcodes.CHECKCAST: return 139;
            case Opcodes.INSTANCEOF: return 140;
            default:
                throw new IllegalArgumentException("Unknown TypeInsnNode opcode: " + opcode);
        }
    }

    private int mapJumpOpcode(int opcode) {
        switch (opcode) {
            case Opcodes.IFEQ: return 103;
            case Opcodes.IFNE: return 104;
            case Opcodes.IFLT: return 105;
            case Opcodes.IFGE: return 106;
            case Opcodes.IFGT: return 107;
            case Opcodes.IFLE: return 108;
            case Opcodes.IF_ICMPEQ: return 109;
            case Opcodes.IF_ICMPNE: return 110;
            case Opcodes.IF_ICMPLT: return 111;
            case Opcodes.IF_ICMPGE: return 112;
            case Opcodes.IF_ICMPGT: return 113;
            case Opcodes.IF_ICMPLE: return 114;
            case Opcodes.IF_ACMPEQ: return 115;
            case Opcodes.IF_ACMPNE: return 116;
            case Opcodes.GOTO: return 117;
            case Opcodes.IFNULL: return 143;
            case Opcodes.IFNONNULL: return 144;
            default:
                throw new IllegalArgumentException("Unknown JumpInsnNode opcode: " + opcode);
        }
    }
}
