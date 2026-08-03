package dev.frost.ir.pass;

import dev.frost.ir.bytecode.JvmTypeAdapter;
import dev.frost.ir.model.BasicBlock;
import dev.frost.ir.model.CoreOps;
import dev.frost.ir.model.IrAttribute;
import dev.frost.ir.model.IrInstruction;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.model.MethodParameter;
import dev.frost.ir.model.Use;
import dev.frost.ir.model.Value;
import dev.frost.ir.transform.IrExpressionBuilder;
import dev.frost.ir.type.IrType;
import dev.frost.ir.type.PrimitiveType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Typed interprocedural parameter encode/decode rewrites over SSA def-use edges. */
public final class ParameterEncryptionPass implements MethodPass {
    public static final String ID = "frost.obfuscate.parameters";

    private final Map<Integer, Long> entryKeys;
    private final Map<MethodRef, Map<Integer, Long>> callsiteKeys;

    private ParameterEncryptionPass(Map<Integer, Long> entryKeys,
                                    Map<MethodRef, Map<Integer, Long>> callsiteKeys) {
        this.entryKeys = copyNestedEntry(entryKeys);
        this.callsiteKeys = copyNested(callsiteKeys);
    }

    public static ParameterEncryptionPass decodeEntry(Map<Integer, Long> argumentKeys) {
        return new ParameterEncryptionPass(argumentKeys, Map.of());
    }

    public static ParameterEncryptionPass encodeCallsites(Map<MethodRef, Map<Integer, Long>> keys) {
        return new ParameterEncryptionPass(Map.of(), keys);
    }

    public static ParameterEncryptionPass rewrite(Map<Integer, Long> entryKeys,
                                                  Map<MethodRef, Map<Integer, Long>> callsiteKeys) {
        return new ParameterEncryptionPass(entryKeys, callsiteKeys);
    }

    @Override public String id() { return ID; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        int entries = rewriteEntry(method);
        int callsites = rewriteCallsites(method);
        if (entries + callsites == 0) return PassResult.unchanged();
        return new PassResult(true, PreservedAnalyses.none(), List.of(), Map.of(
                "entry_parameters", (long) entries, "callsites", (long) callsites));
    }

    private int rewriteEntry(IrMethod method) {
        if (entryKeys.isEmpty()) return 0;
        BasicBlock entry = method.entryBlock().orElse(null);
        if (entry == null) return 0;
        int parameterOffset = (method.signature().access() & org.objectweb.asm.Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        int changed = 0;
        for (Map.Entry<Integer, Long> keyed : entryKeys.entrySet()) {
            int parameterIndex = parameterOffset + keyed.getKey();
            if (parameterIndex < 0 || parameterIndex >= method.parameters().size()) continue;
            MethodParameter parameter = method.parameters().get(parameterIndex);
            if (!encryptable(parameter.value().type())) continue;
            List<Use> originalUses = new ArrayList<>(parameter.value().uses());
            IrExpressionBuilder builder = new IrExpressionBuilder(method, entry, 0);
            Value key = builder.constant(normalize(keyed.getValue(), parameter.value().type()), parameter.value().type());
            Value decoded = builder.binary(CoreOps.XOR, parameter.value(), key, parameter.value().type());
            originalUses.forEach(use -> use.replaceWith(decoded));
            changed++;
        }
        return changed;
    }

    private int rewriteCallsites(IrMethod method) {
        if (callsiteKeys.isEmpty()) return 0;
        int changed = 0;
        List<IrInstruction> calls = method.blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.operation().code().equals(CoreOps.INVOKE)).toList();
        for (IrInstruction call : calls) {
            MethodRef ref = methodRef(call);
            Map<Integer, Long> keys = ref == null ? null : callsiteKeys.get(ref);
            if (keys == null || keys.isEmpty()) continue;
            boolean isStatic = string(call, "invoke_kind").equals("INVOKESTATIC");
            List<IrType> parameterTypes = JvmTypeAdapter.methodType(ref.descriptor()).parameterTypes();
            BasicBlock block = call.block().orElseThrow();
            IrExpressionBuilder builder = new IrExpressionBuilder(method, block, block.instructions().indexOf(call));
            boolean rewritten = false;
            for (Map.Entry<Integer, Long> keyed : keys.entrySet()) {
                int argument = keyed.getKey();
                int operand = (isStatic ? 0 : 1) + argument;
                if (argument < 0 || argument >= parameterTypes.size() || operand >= call.operands().size()) continue;
                Value original = call.operands().get(operand);
                IrType declared = parameterTypes.get(argument);
                if (!sameJvmComputation(original.type(), declared) || !encryptable(declared)) continue;
                Value key = builder.constant(normalize(keyed.getValue(), original.type()), original.type());
                Value encoded = builder.binary(CoreOps.XOR, original, key, original.type());
                call.setOperand(operand, encoded);
                rewritten = true;
            }
            if (rewritten) changed++;
        }
        return changed;
    }

    private MethodRef methodRef(IrInstruction call) {
        String owner = string(call, "owner"), name = string(call, "name"), descriptor = string(call, "descriptor");
        return owner.isEmpty() || name.isEmpty() || descriptor.isEmpty() ? null : new MethodRef(owner, name, descriptor);
    }

    private String string(IrInstruction instruction, String name) {
        return instruction.operation().attributes().get(name) instanceof IrAttribute.StringValue text
                ? text.value() : "";
    }

    private boolean encryptable(IrType type) {
        return type instanceof PrimitiveType primitive
                && (primitive.computationalType() == PrimitiveType.INT || primitive == PrimitiveType.LONG);
    }

    private boolean sameJvmComputation(IrType actual, IrType declared) {
        if (actual.equals(declared)) return true;
        return actual instanceof PrimitiveType left && declared instanceof PrimitiveType right
                && left.computationalType() == right.computationalType();
    }

    private long normalize(long value, IrType type) {
        return type == PrimitiveType.LONG ? value : (int) value;
    }

    private static Map<Integer, Long> copyNestedEntry(Map<Integer, Long> source) {
        return Map.copyOf(source == null ? Map.of() : new LinkedHashMap<>(source));
    }

    private static Map<MethodRef, Map<Integer, Long>> copyNested(Map<MethodRef, Map<Integer, Long>> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<MethodRef, Map<Integer, Long>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key), Map.copyOf(value)));
        return Map.copyOf(copy);
    }

    public record MethodRef(String owner, String name, String descriptor) {
        public MethodRef {
            Objects.requireNonNull(owner); Objects.requireNonNull(name); Objects.requireNonNull(descriptor);
        }
    }
}
