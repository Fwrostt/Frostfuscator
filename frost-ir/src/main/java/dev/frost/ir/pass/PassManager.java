package dev.frost.ir.pass;

import dev.frost.ir.core.Diagnostic;
import dev.frost.ir.model.IrMethod;
import dev.frost.ir.verify.IrValidator;
import dev.frost.ir.verify.ValidationProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic method pipeline with pass-contract checking and validation boundaries. */
public final class PassManager {
    private final List<MethodPass> passes = new ArrayList<>();
    private final List<PassListener> listeners = new ArrayList<>();
    private final IrValidator validator = new IrValidator();
    private ValidationProfile validationProfile = ValidationProfile.STRICT;
    private boolean validateAfterEachPass = true;

    public PassManager add(MethodPass pass) { passes.add(Objects.requireNonNull(pass, "pass")); return this; }
    public PassManager addListener(PassListener listener) { listeners.add(Objects.requireNonNull(listener, "listener")); return this; }
    public PassManager validationProfile(ValidationProfile profile) { validationProfile = Objects.requireNonNull(profile); return this; }
    public PassManager validateAfterEachPass(boolean value) { validateAfterEachPass = value; return this; }
    public List<MethodPass> passes() { return List.copyOf(passes); }

    public PipelineResult run(IrMethod method, PassContext context) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(context, "context");
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, Map<String, Long>> metrics = new LinkedHashMap<>();
        validator.validate(method, validationProfile).throwIfInvalid();
        int changedPasses = 0;
        List<PassExecution> trace = new ArrayList<>();
        for (MethodPass pass : passes) {
            long before = method.revision();
            listeners.forEach(listener -> listener.beforePass(method, pass, before));
            long started = System.nanoTime();
            PassResult result;
            try (IrMethod.Mutation ignored = method.beginMutation("pass:" + pass.id())) {
                result = Objects.requireNonNull(pass.run(method, context), "pass result");
            }
            long after = method.revision();
            long elapsed = Math.max(0, System.nanoTime() - started);
            boolean actuallyChanged = before != after;
            if (actuallyChanged != result.changed()) {
                throw new IllegalStateException("Pass " + pass.id() + " reported changed=" + result.changed()
                        + " but method revision changed=" + actuallyChanged);
            }
            diagnostics.addAll(result.diagnostics());
            metrics.put(pass.id(), result.metrics());
            PassExecution execution = new PassExecution(pass.id(), before, after, actuallyChanged, elapsed, result.metrics());
            trace.add(execution);
            if (actuallyChanged) {
                changedPasses++;
                context.analyses().invalidate(method, result.preservedAnalyses(), before, after);
            }
            if (validateAfterEachPass) validator.validate(method, validationProfile).throwIfInvalid();
            listeners.forEach(listener -> listener.afterPass(method, pass, execution));
        }
        if (!validateAfterEachPass) validator.validate(method, validationProfile).throwIfInvalid();
        return new PipelineResult(changedPasses > 0, changedPasses, diagnostics, metrics, trace);
    }

    public record PipelineResult(boolean changed, int changedPasses, List<Diagnostic> diagnostics,
                                 Map<String, Map<String, Long>> metrics, List<PassExecution> trace) {
        public PipelineResult {
            diagnostics = List.copyOf(diagnostics);
            Map<String, Map<String, Long>> copy = new LinkedHashMap<>();
            metrics.forEach((key, value) -> copy.put(key, Map.copyOf(value)));
            metrics = Map.copyOf(copy);
            trace = List.copyOf(trace);
        }
    }
}
