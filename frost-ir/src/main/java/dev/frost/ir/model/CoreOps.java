package dev.frost.ir.model;

import java.util.List;

/** Stable built-in Frost dialect. JVM-specific payloads are immutable operation attributes. */
public final class CoreOps {
    private CoreOps() {}

    public static final OperationCode CONSTANT = op("constant");
    public static final OperationCode NOP = op("nop");
    public static final OperationCode COPY = op("copy");
    public static final OperationCode SELECT = op("select");
    public static final OperationCode ADD = op("add");
    public static final OperationCode SUB = op("sub");
    public static final OperationCode MUL = op("mul");
    public static final OperationCode DIV = op("div");
    public static final OperationCode REM = op("rem");
    public static final OperationCode NEG = op("neg");
    public static final OperationCode AND = op("and");
    public static final OperationCode OR = op("or");
    public static final OperationCode XOR = op("xor");
    public static final OperationCode SHL = op("shl");
    public static final OperationCode SHR = op("shr");
    public static final OperationCode USHR = op("ushr");
    public static final OperationCode COMPARE = op("compare");
    public static final OperationCode CONVERT = op("convert");
    public static final OperationCode NEW_OBJECT = jvm("new_object");
    public static final OperationCode NEW_ARRAY = jvm("new_array");
    public static final OperationCode ARRAY_LENGTH = jvm("array_length");
    public static final OperationCode ARRAY_LOAD = jvm("array_load");
    public static final OperationCode ARRAY_STORE = jvm("array_store");
    public static final OperationCode FIELD_LOAD = jvm("field_load");
    public static final OperationCode FIELD_STORE = jvm("field_store");
    public static final OperationCode STATIC_LOAD = jvm("static_load");
    public static final OperationCode STATIC_STORE = jvm("static_store");
    public static final OperationCode CHECK_CAST = jvm("check_cast");
    public static final OperationCode INSTANCE_OF = jvm("instance_of");
    public static final OperationCode INVOKE = jvm("invoke");
    public static final OperationCode INITIALIZE = jvm("initialize");
    public static final OperationCode INVOKE_DYNAMIC = jvm("invoke_dynamic");
    public static final OperationCode CONSTANT_DYNAMIC = jvm("constant_dynamic");
    public static final OperationCode MONITOR_ENTER = jvm("monitor_enter");
    public static final OperationCode MONITOR_EXIT = jvm("monitor_exit");
    public static final OperationCode CATCH = jvm("catch");
    public static final OperationCode LOCAL_WRITE = jvm("local_write");
    public static final OperationCode STACK_PERMUTE = jvm("stack_permute");
    public static final OperationCode OPAQUE_PURE_BYTECODE = jvm("opaque_pure_bytecode");
    public static final OperationCode OPAQUE_BYTECODE = jvm("opaque_bytecode");
    public static final OperationCode OPAQUE_TERMINATOR = jvm("opaque_terminator");
    public static final OperationCode BRANCH = control("branch");
    public static final OperationCode CONDITIONAL_BRANCH = control("conditional_branch");
    public static final OperationCode SWITCH = control("switch");
    public static final OperationCode RETURN = control("return");
    public static final OperationCode THROW = control("throw");
    public static final OperationCode UNREACHABLE = control("unreachable");

    public static List<OperationSchema> schemas() {
        var binary = List.of(ADD, SUB, MUL, DIV, REM, AND, OR, XOR, SHL, SHR, USHR, COMPARE);
        java.util.ArrayList<OperationSchema> schemas = new java.util.ArrayList<>();
        schemas.add(OperationSchema.builder(CONSTANT).operands(0).results(1)
                .traits(OperationTrait.CONSTANT_LIKE, OperationTrait.SPECULATABLE).build());
        schemas.add(OperationSchema.builder(NOP).operands(0).results(0).traits(OperationTrait.SPECULATABLE).build());
        schemas.add(OperationSchema.builder(COPY).operands(1).results(1).traits(OperationTrait.SPECULATABLE).build());
        schemas.add(OperationSchema.builder(SELECT).operands(3).results(1).traits(OperationTrait.SPECULATABLE).build());
        for (OperationCode code : binary) {
            EffectSummary effects = code == DIV || code == REM ? EffectSummary.of(Effect.MAY_THROW) : EffectSummary.PURE;
            schemas.add(OperationSchema.builder(code).operands(2).results(1)
                    .traits(OperationTrait.SPECULATABLE).effects(effects).build());
        }
        schemas.add(OperationSchema.builder(NEG).operands(1).results(1).traits(OperationTrait.SPECULATABLE).build());
        schemas.add(OperationSchema.builder(CONVERT).operands(1).results(1).traits(OperationTrait.SPECULATABLE).build());
        schemas.add(OperationSchema.builder(NEW_OBJECT).operands(0).results(1)
                .traits(OperationTrait.ALLOCATION).effects(EffectSummary.of(Effect.ALLOCATE, Effect.MAY_THROW)).build());
        schemas.add(OperationSchema.builder(NEW_ARRAY).variadicOperands(1).results(1)
                .traits(OperationTrait.ALLOCATION).effects(EffectSummary.of(Effect.ALLOCATE, Effect.MAY_THROW)).build());
        schemas.add(OperationSchema.builder(ARRAY_LENGTH).operands(1).results(1)
                .traits(OperationTrait.MEMORY_READ).effects(EffectSummary.of(Effect.READ_ARRAY, Effect.MAY_THROW)).build());
        schemas.add(OperationSchema.builder(ARRAY_LOAD).operands(2).results(1)
                .traits(OperationTrait.MEMORY_READ).effects(EffectSummary.of(Effect.READ_ARRAY, Effect.MAY_THROW)).build());
        schemas.add(OperationSchema.builder(ARRAY_STORE).operands(3).results(0)
                .traits(OperationTrait.MEMORY_WRITE).effects(EffectSummary.of(Effect.WRITE_ARRAY, Effect.MAY_THROW)).build());
        schemas.add(OperationSchema.builder(FIELD_LOAD).operands(1).results(1)
                .traits(OperationTrait.MEMORY_READ).effects(EffectSummary.of(Effect.READ_HEAP, Effect.MAY_THROW)).build());
        schemas.add(OperationSchema.builder(FIELD_STORE).operands(2).results(0)
                .traits(OperationTrait.MEMORY_WRITE).effects(EffectSummary.of(Effect.WRITE_HEAP, Effect.MAY_THROW)).build());
        schemas.add(OperationSchema.builder(STATIC_LOAD).operands(0).results(1)
                .traits(OperationTrait.MEMORY_READ).effects(EffectSummary.of(Effect.READ_STATIC, Effect.MAY_THROW)).build());
        schemas.add(OperationSchema.builder(STATIC_STORE).operands(1).results(0)
                .traits(OperationTrait.MEMORY_WRITE).effects(EffectSummary.of(Effect.WRITE_STATIC, Effect.MAY_THROW)).build());
        schemas.add(OperationSchema.builder(CHECK_CAST).operands(1).results(1).effects(EffectSummary.of(Effect.MAY_THROW)).build());
        schemas.add(OperationSchema.builder(INSTANCE_OF).operands(1).results(1).traits(OperationTrait.SPECULATABLE).build());
        EffectSummary call = EffectSummary.of(Effect.INVOKE, Effect.MAY_THROW, Effect.UNKNOWN);
        schemas.add(OperationSchema.builder(INVOKE).variadicOperands(0).results(0, 1)
                .traits(OperationTrait.CALL_LIKE).effects(call).build());
        schemas.add(OperationSchema.builder(INITIALIZE).variadicOperands(1).results(1)
                .traits(OperationTrait.CALL_LIKE).effects(call).build());
        schemas.add(OperationSchema.builder(INVOKE_DYNAMIC).variadicOperands(0).results(0, 1)
                .traits(OperationTrait.CALL_LIKE).effects(call.union(EffectSummary.of(Effect.DYNAMIC_LINKAGE))).build());
        schemas.add(OperationSchema.builder(CONSTANT_DYNAMIC).operands(0).results(1)
                .effects(EffectSummary.of(Effect.DYNAMIC_LINKAGE, Effect.MAY_THROW)).build());
        schemas.add(OperationSchema.builder(MONITOR_ENTER).operands(1).results(0)
                .traits(OperationTrait.PINNED).effects(EffectSummary.of(Effect.MONITOR, Effect.MAY_THROW)).build());
        schemas.add(OperationSchema.builder(MONITOR_EXIT).operands(1).results(0)
                .traits(OperationTrait.PINNED).effects(EffectSummary.of(Effect.MONITOR, Effect.MAY_THROW)).build());
        schemas.add(OperationSchema.builder(CATCH).operands(0).results(1).traits(OperationTrait.PINNED).build());
        schemas.add(OperationSchema.builder(LOCAL_WRITE).operands(1).results(0)
                .traits(OperationTrait.JVM_PSEUDO, OperationTrait.SPECULATABLE).build());
        schemas.add(OperationSchema.builder(STACK_PERMUTE).operands(0, 4).results(0)
                .traits(OperationTrait.JVM_PSEUDO, OperationTrait.SPECULATABLE).build());
        schemas.add(OperationSchema.builder(OPAQUE_PURE_BYTECODE).variadicOperands(0).results(0, Integer.MAX_VALUE)
                .traits(OperationTrait.OPAQUE, OperationTrait.JVM_PSEUDO).build());
        schemas.add(OperationSchema.builder(OPAQUE_BYTECODE).variadicOperands(0).results(0, Integer.MAX_VALUE)
                .traits(OperationTrait.OPAQUE, OperationTrait.JVM_PSEUDO).effects(EffectSummary.UNKNOWN).build());
        schemas.add(OperationSchema.builder(OPAQUE_TERMINATOR).variadicOperands(0).results(0, Integer.MAX_VALUE)
                .traits(OperationTrait.OPAQUE, OperationTrait.JVM_PSEUDO, OperationTrait.TERMINATOR)
                .effects(EffectSummary.of(Effect.CONTROL_FLOW, Effect.MAY_THROW, Effect.UNKNOWN)).build());
        schemas.add(terminator(BRANCH, 0));
        schemas.add(OperationSchema.builder(CONDITIONAL_BRANCH).operands(1, 2).results(0)
                .traits(OperationTrait.TERMINATOR).effects(EffectSummary.of(Effect.CONTROL_FLOW)).build());
        schemas.add(terminator(SWITCH, 1));
        schemas.add(OperationSchema.builder(RETURN).operands(0, 1).results(0)
                .traits(OperationTrait.TERMINATOR).effects(EffectSummary.of(Effect.CONTROL_FLOW)).build());
        schemas.add(OperationSchema.builder(THROW).operands(1).results(0)
                .traits(OperationTrait.TERMINATOR).effects(EffectSummary.of(Effect.CONTROL_FLOW, Effect.MAY_THROW)).build());
        schemas.add(terminator(UNREACHABLE, 0));
        return List.copyOf(schemas);
    }

    private static OperationSchema terminator(OperationCode code, int operands) {
        return OperationSchema.builder(code).operands(operands).results(0)
                .traits(OperationTrait.TERMINATOR).effects(EffectSummary.of(Effect.CONTROL_FLOW)).build();
    }
    private static OperationCode op(String name) { return new OperationCode("frost", name); }
    private static OperationCode jvm(String name) { return new OperationCode("frost.jvm", name); }
    private static OperationCode control(String name) { return new OperationCode("frost.control", name); }
}
