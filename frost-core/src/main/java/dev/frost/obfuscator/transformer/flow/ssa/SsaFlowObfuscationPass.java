package dev.frost.obfuscator.transformer.flow.ssa;

import dev.frost.ir.model.IrMethod;
import dev.frost.ir.pass.MethodPass;
import dev.frost.ir.pass.PassContext;
import dev.frost.ir.pass.PassResult;
import dev.frost.ir.pass.PreservedAnalyses;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Atomic Phase 3.1 + Phase 4.4 production pipeline for the combined flow transformer. */
public final class SsaFlowObfuscationPass implements MethodPass {
    private final SsaFlowFlatteningPass flattening;
    private final SsaFlowExceptionPass exceptions;
    private final SsaFlowSwitchPass switches;
    private final SsaOpaquePredicatePass predicates;
    private final boolean flatten;
    private final boolean exceptionGuards;
    private final boolean rewriteSwitches;

    public SsaFlowObfuscationPass(boolean flatten, boolean exceptionGuards, boolean rewriteSwitches,
                                  SsaFlowFlatteningPass flattening, SsaFlowExceptionPass exceptions,
                                  SsaFlowSwitchPass switches, SsaOpaquePredicatePass predicates) {
        this.flatten = flatten;
        this.exceptionGuards = exceptionGuards;
        this.rewriteSwitches = rewriteSwitches;
        this.flattening = flattening;
        this.exceptions = exceptions;
        this.switches = switches;
        this.predicates = predicates;
    }

    @Override public String id() { return "frost.flow.obfuscation-ssa"; }

    @Override
    public PassResult run(IrMethod method, PassContext context) {
        PassResult flattened = flatten ? flattening.run(method, context) : PassResult.unchanged();
        if (flattened.changed()) context.analyses().clear(method);
        PassResult switched = rewriteSwitches ? switches.run(method, context) : PassResult.unchanged();
        if (switched.changed()) context.analyses().clear(method);
        PassResult guardedExceptions = exceptionGuards ? exceptions.run(method, context) : PassResult.unchanged();
        if (guardedExceptions.changed()) context.analyses().clear(method);
        PassResult guarded = predicates.run(method, context);
        if (!flattened.changed() && !switched.changed() && !guardedExceptions.changed() && !guarded.changed()) {
            return PassResult.unchanged();
        }
        Map<String, Long> metrics = new LinkedHashMap<>();
        flattened.metrics().forEach((name, value) -> metrics.merge(name, value, Long::sum));
        switched.metrics().forEach((name, value) -> metrics.merge(name, value, Long::sum));
        guardedExceptions.metrics().forEach((name, value) -> metrics.merge(name, value, Long::sum));
        guarded.metrics().forEach((name, value) -> metrics.merge(name, value, Long::sum));
        return new PassResult(true, PreservedAnalyses.none(), List.of(), metrics);
    }
}
