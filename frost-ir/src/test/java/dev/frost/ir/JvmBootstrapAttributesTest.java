package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.frost.ir.bytecode.JvmBootstrapAttributes;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

class JvmBootstrapAttributesTest {
    @Test
    void roundTripsNestedBootstrapPayloadWithoutAsmSourceProvenance() {
        Handle nestedBootstrap = new Handle(Opcodes.H_INVOKESTATIC, "fixture/Bootstrap", "nested",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;I)Ljava/lang/Object;",
                false);
        ConstantDynamic nested = new ConstantDynamic("key", "I", nestedBootstrap, 17);
        Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, "fixture/Bootstrap", "link",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);

        Map<String, dev.frost.ir.model.IrAttribute> attributes = JvmBootstrapAttributes.dynamicCallSite(
                "call", "(I)Ljava/lang/String;", bootstrap, "payload", 3L, 1.5f,
                Type.getMethodType("(J)V"), nested);

        assertEquals(bootstrap, JvmBootstrapAttributes.bootstrapHandle(attributes));
        Object[] decoded = JvmBootstrapAttributes.bootstrapArguments(attributes);
        assertEquals("payload", decoded[0]);
        assertEquals(3L, decoded[1]);
        assertEquals(1.5f, decoded[2]);
        assertEquals(Type.getMethodType("(J)V"), decoded[3]);
        ConstantDynamic decodedNested = (ConstantDynamic) decoded[4];
        assertEquals(nested.getName(), decodedNested.getName());
        assertEquals(nested.getDescriptor(), decodedNested.getDescriptor());
        assertEquals(nested.getBootstrapMethod(), decodedNested.getBootstrapMethod());
        assertEquals(17, decodedNested.getBootstrapMethodArgument(0));
    }
}
