package dev.frost.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frost.ir.analysis.StandardAnalyses;
import dev.frost.ir.core.IrContext;
import dev.frost.ir.model.OperationCode;
import dev.frost.ir.model.OperationSchema;
import dev.frost.ir.pass.PassResult;
import dev.frost.ir.plugin.IrPlugin;
import dev.frost.ir.plugin.IrPluginDescriptor;
import dev.frost.ir.plugin.IrPluginRegistrar;
import dev.frost.ir.serialization.FrozenMethodJsonCodec;
import dev.frost.ir.serialization.FrozenMethodBinaryCodec;
import dev.frost.ir.serialization.IrSerializationException;
import dev.frost.ir.snapshot.IrFreezer;
import org.junit.jupiter.api.Test;
import java.util.Arrays;

class SerializationAndPluginTest {
    @Test
    void snapshotJsonIsDeterministicLosslessAndTamperEvident() {
        var frozen = new IrFreezer().freeze(FrostIrTestFixtures.diamond().method());
        FrozenMethodJsonCodec codec = new FrozenMethodJsonCodec();
        String first = codec.serialize(frozen);
        String second = codec.serialize(frozen);
        assertEquals(first, second);
        assertEquals(frozen, codec.deserialize(first));

        String tampered = first.replaceFirst("\"sourceRevision\":\"[0-9]+\"",
                "\"sourceRevision\":\"999\"");
        assertThrows(IrSerializationException.class, () -> codec.deserialize(tampered));
    }

    @Test
    void binarySnapshotsAreDeterministicBoundedAndChecksummed() {
        var frozen = new IrFreezer().freeze(FrostIrTestFixtures.diamond().method());
        FrozenMethodBinaryCodec codec = new FrozenMethodBinaryCodec();
        byte[] first = codec.serialize(frozen), second = codec.serialize(frozen);
        assertTrue(Arrays.equals(first, second));
        assertEquals(frozen, codec.deserialize(first));
        first[first.length - 1] ^= 1;
        assertThrows(IrSerializationException.class, () -> codec.deserialize(first));
    }

    @Test
    void installsVersionedPluginsAndCreatesRegisteredExtensions() {
        OperationCode code = new OperationCode("test.plugin", "marker");
        IrPlugin plugin = new IrPlugin() {
            @Override public IrPluginDescriptor descriptor() { return new IrPluginDescriptor("test-plugin", "1.0.0"); }
            @Override public void register(IrPluginRegistrar registrar) {
                registrar.registerOperation(OperationSchema.builder(code).operands(0).results(0).build());
                registrar.registerAnalysis(StandardAnalyses.GVN);
                registrar.registerPass("test.noop", () -> new dev.frost.ir.pass.MethodPass() {
                    @Override public String id() { return "test.noop"; }
                    @Override public PassResult run(dev.frost.ir.model.IrMethod method,
                                                    dev.frost.ir.pass.PassContext context) {
                        return PassResult.unchanged();
                    }
                });
            }
        };
        IrContext context = IrContext.builder().install(plugin).build();
        assertEquals(plugin.descriptor(), context.plugins().get("test-plugin"));
        assertTrue(context.schema(code).isPresent());
        assertTrue(context.analysis(StandardAnalyses.GVN.key()).isPresent());
        assertEquals("test.noop", context.createPass("test.noop").orElseThrow().id());
    }

    @Test
    void failedPluginInstallRollsBackEveryRegistration() {
        OperationCode code = new OperationCode("test.rollback", "marker");
        IrContext.Builder builder = IrContext.builder();
        IrPlugin invalid = new IrPlugin() {
            @Override public IrPluginDescriptor descriptor() { return new IrPluginDescriptor("broken", "1"); }
            @Override public void register(IrPluginRegistrar registrar) {
                OperationSchema schema = OperationSchema.builder(code).operands(0).results(0).build();
                registrar.registerOperation(schema);
                registrar.registerOperation(schema);
            }
        };
        assertThrows(IllegalArgumentException.class, () -> builder.install(invalid));
        builder.register(OperationSchema.builder(code).operands(0).results(0).build());
        assertTrue(builder.build().schema(code).isPresent());
    }
}
