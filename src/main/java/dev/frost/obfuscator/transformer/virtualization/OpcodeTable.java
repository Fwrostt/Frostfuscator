package dev.frost.obfuscator.transformer.virtualization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class OpcodeTable {
    private final int[] customToInternal = new int[256];
    private final int[] internalToCustom = new int[256];

    public OpcodeTable(Random random) {
        // OP_TRAP = 145
        for (int i = 0; i < 256; i++) {
            customToInternal[i] = 145;
        }
        for (int i = 0; i < 256; i++) {
            internalToCustom[i] = -1;
        }

        List<Integer> customOpcodes = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            customOpcodes.add(i);
        }
        Collections.shuffle(customOpcodes, random);

        int customIdx = 0;
        // OP_NOP to OP_STORE2
        for (int internalOp = 0; internalOp <= 147; internalOp++) {
            int customOp = customOpcodes.get(customIdx++);
            customToInternal[customOp] = internalOp;
            internalToCustom[internalOp] = customOp;
        }
    }

    public byte encode(int internalOp) {
        int custom = internalToCustom[internalOp];
        if (custom == -1) {
            throw new IllegalArgumentException("Unknown internal opcode: " + internalOp);
        }
        return (byte) custom;
    }

    public int[] getDecodingTable() {
        return customToInternal;
    }
}
