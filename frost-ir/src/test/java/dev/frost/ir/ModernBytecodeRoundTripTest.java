package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.ir.bytecode.BytecodeClassImporter;
import dev.frost.ir.bytecode.BytecodeClassLowerer;
import dev.frost.ir.bytecode.ImportCapability;
import dev.frost.ir.core.IrContext;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

class ModernBytecodeRoundTripTest {
    @Test
    void liftsLowersAndExecutesJavacSynchronizedLambdaAndFinallyBytecode() throws Exception {
        byte[] original = classBytes(ModernFixture.class);
        var imported = new BytecodeClassImporter(IrContext.standard()).importClass(original);
        assertTrue(imported.diagnostics().stream().noneMatch(diagnostic -> diagnostic.severity().ordinal() >= 2),
                () -> imported.diagnostics().toString());
        for (var entry : imported.methods().entrySet()) {
            if ((entry.getValue().method().signature().access()
                    & (org.objectweb.asm.Opcodes.ACC_ABSTRACT | org.objectweb.asm.Opcodes.ACC_NATIVE)) == 0) {
                assertTrue(entry.getValue().has(ImportCapability.TYPED_STACK_SSA),
                        () -> "not typed: " + entry.getKey() + " " + entry.getValue().diagnostics());
            }
        }

        imported.methods().entrySet().stream()
                .filter(entry -> entry.getKey().name().equals("synchronizedAdd"))
                .findFirst().orElseThrow().getValue().method().parameters().getFirst().value().setDebugName("monitor");
        imported.methods().entrySet().stream()
                .filter(entry -> entry.getKey().name().equals("lambdaAdd"))
                .findFirst().orElseThrow().getValue().method().parameters().getFirst().value().setDebugName("captured");

        var lowered = new BytecodeClassLowerer().lower(imported);
        assertTrue(lowered.succeeded(), () -> lowered.diagnostics().toString());
        String binaryName = new ClassReader(original).getClassName().replace('/', '.');
        byte[] loweredBytes = lowered.output().orElseThrow();
        Class<?> reloaded = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(binaryName, loweredBytes, 0, loweredBytes.length); }
        }.define();

        Method synchronizedAdd = reloaded.getDeclaredMethod("synchronizedAdd", Object.class, int.class);
        Method lambdaAdd = reloaded.getDeclaredMethod("lambdaAdd", int.class);
        Method finallyDivide = reloaded.getDeclaredMethod("finallyDivide", int.class);
        synchronizedAdd.setAccessible(true);
        lambdaAdd.setAccessible(true);
        finallyDivide.setAccessible(true);
        assertEquals(8, synchronizedAdd.invoke(null, new Object(), 7));
        assertEquals(13, lambdaAdd.invoke(null, 10));
        assertEquals(5, finallyDivide.invoke(null, 2));
        try {
            finallyDivide.invoke(null, 0);
        } catch (java.lang.reflect.InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof ArithmeticException);
        }
        var sink = reloaded.getDeclaredField("sink");
        sink.setAccessible(true);
        assertEquals(2, sink.getInt(null));
    }

    private byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing class resource " + resource);
            return input.readAllBytes();
        }
    }

    static final class ModernFixture {
        private static int sink;

        static int synchronizedAdd(Object monitor, int value) {
            synchronized (monitor) {
                return value + 1;
            }
        }

        static int lambdaAdd(int captured) {
            IntUnaryOperator operator = value -> value + captured;
            return operator.applyAsInt(3);
        }

        static int finallyDivide(int divisor) {
            try {
                return 10 / divisor;
            } finally {
                sink++;
            }
        }
    }
}
