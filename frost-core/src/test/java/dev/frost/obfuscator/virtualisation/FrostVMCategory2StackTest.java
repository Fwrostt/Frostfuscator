package dev.frost.obfuscator.virtualisation;

import dev.frost.loader.FrostVM;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrostVMCategory2StackTest {
    @Test
    void dup2DuplicatesLongAndDoubleValues() throws Throwable {
        assertEquals(2L, execute(10, 42, 47, 121));
        assertEquals(2.0d, (Double) execute(15, 42, 49, 123), 0.0d);
    }

    @Test
    void category2DupInsertionFormsPreserveLogicalValues() throws Throwable {
        // ICONST_2, LCONST_1, DUP2_X1, POP2, POP, LRETURN
        assertEquals(1L, execute(5, 10, 43, 38, 37, 121));
        // LCONST_0, LCONST_1, DUP2_X2, LADD, LADD, LRETURN
        assertEquals(2L, execute(9, 10, 44, 47, 47, 121));
        // LCONST_1, ICONST_2, DUP_X2, POP, LRETURN
        assertEquals(1L, execute(10, 5, 41, 37, 121));
    }

    private static Object execute(int... opcodes) throws Throwable {
        byte[] bytecode = new byte[opcodes.length];
        for (int index = 0; index < opcodes.length; index++) bytecode[index] = (byte) opcodes[index];
        return FrostVM.execute(bytecode, new Object[0], new Object[0], new int[0], 0, 16);
    }
}
